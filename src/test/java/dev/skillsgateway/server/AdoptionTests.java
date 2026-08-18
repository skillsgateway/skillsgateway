package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.approval.VettingBlockedException;
import dev.skillsgateway.server.observability.GatewayMetrics;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adoption and staleness reporting off the fetch ledger (GW_0075, GW_0076) and the always-recorded
 * gateway metrics (GW_0077). All fetches here are real git clones through the facade, so the
 * ledger rows under aggregation are exactly the rows production writes; the authorization walk of
 * the two reads lives in RoleEnforcementTests, whose enforcing context classifies them as
 * privileged reads.
 */
class AdoptionTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The shapes VettingTests plants: a formed AWS key id and a PEM header, belonging to nobody. */
    private static final String PLANTED_SECRETS = """
            AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE

            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAxGZQ0000000000000000000000000000000000000000000000
            -----END RSA PRIVATE KEY-----
            """;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private GitStorage gitStorage;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @SVCs({"SVC_GW_0075"})
    void the_adoption_report_aggregates_the_windowed_ledger_per_marketplace_sha_and_identity() throws Exception {
        String name = uniqueName("adopt");
        Registered fixture = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        String sha = fixture.snapshot().sha();
        approve(fixture.snapshot().id());

        String alice = uniqueName("ada");
        String bob = uniqueName("bob");
        clone(name, alice);
        clone(name, bob);

        // A fetch older than the window, planted directly on the ledger: the report must not
        // count it at 30 days and must count it at 365.
        jdbc.sql("INSERT INTO fetch_log (ts, source, principal, marketplace, event, ref, sha)"
                        + " VALUES (:ts, '127.0.0.1', :principal, :marketplace, 'upload-pack',"
                        + " 'refs/heads/main', :sha)")
                .param("ts", OffsetDateTime.now().minus(Duration.ofDays(40)))
                .param("principal", uniqueName("oldtimer"))
                .param("marketplace", name)
                .param("sha", sha)
                .update();

        JsonNode windowed = marketplaceEntry(adoption("/api/adoption?days=30"), name);
        assertThat(windowed.get("fetches").asLong()).isEqualTo(2);
        assertThat(windowed.get("identities").asLong()).isEqualTo(2);
        assertThat(windowed.get("servedSha").asText()).isEqualTo(sha);
        assertThat(windowed.get("lastFetch").asText()).isNotEmpty();
        JsonNode breakdown = windowed.get("snapshots");
        assertThat(breakdown).hasSize(1);
        assertThat(breakdown.get(0).get("sha").asText()).isEqualTo(sha);
        assertThat(breakdown.get(0).get("fetches").asLong()).isEqualTo(2);
        assertThat(breakdown.get(0).get("identities").asLong()).isEqualTo(2);
        assertThat(breakdown.get(0).get("current").asBoolean()).isTrue();

        JsonNode yearWide = marketplaceEntry(adoption("/api/adoption?days=365"), name);
        assertThat(yearWide.get("fetches").asLong()).isEqualTo(3);
        assertThat(yearWide.get("identities").asLong()).isEqualTo(3);
    }

    @Test
    @SVCs({"SVC_GW_0076"})
    void staleness_names_exactly_the_identities_not_on_the_served_tip_retracted_content_included() throws Exception {
        String name = uniqueName("stale");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered first = registerAndIngest(name, upstream);
        String v1 = first.snapshot().sha();
        approve(first.snapshot().id());

        String alice = uniqueName("ada");
        String bob = uniqueName("bob");
        clone(name, alice); // alice received v1

        addUpstreamCommit(upstream, "newer content");
        long secondId = ingestionService.ingest(first.marketplace()).id();
        String v2 = approve(secondId).sha();
        clone(name, bob); // bob received the new tip

        List<JsonNode> stale = stalenessOf(name);
        assertThat(stale).hasSize(1);
        JsonNode entry = stale.get(0);
        assertThat(entry.get("principal").asText()).isEqualTo(alice);
        assertThat(entry.get("sha").asText()).isEqualTo(v1);
        assertThat(entry.get("servedSha").asText()).isEqualTo(v2);
        assertThat(entry.get("lastFetch").asText()).isNotEmpty();

        // The marketplace stops serving entirely: every holder of its content is now stale, and
        // there is no tip to diverge from — that is what a retraction leaves behind.
        assertThat(gitStorage.unpublish(name, v2)).isTrue();
        List<JsonNode> afterRetraction = stalenessOf(name);
        assertThat(afterRetraction)
                .extracting(node -> node.get("principal").asText())
                .containsExactlyInAnyOrder(alice, bob);
        for (JsonNode holder : afterRetraction) {
            assertThat(holder.get("servedSha").isNull()).isTrue();
        }
    }

    @Test
    @SVCs({"SVC_GW_0077"})
    void the_skills_gateway_metrics_are_recorded_with_export_left_at_its_disabled_default() throws Exception {
        // The context runs with the repository's default telemetry posture: no export enabled.
        String name = uniqueName("metrics");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered fixture = registerAndIngest(name, upstream);
        approve(fixture.snapshot().id());
        addUpstreamCommit(upstream, "to be rejected");
        long rejectedId = ingestionService.ingest(fixture.marketplace()).id();
        approvalService.reject(rejectedId, "alice");
        clone(name, uniqueName("carol"));

        Timer ingestion = meterRegistry
                .find(GatewayMetrics.INGESTION)
                .tag("outcome", "success")
                .timer();
        assertThat(ingestion).isNotNull();
        assertThat(ingestion.count()).isGreaterThanOrEqualTo(2);

        Timer approvals = meterRegistry
                .find(GatewayMetrics.APPROVAL)
                .tag("decision", "approve")
                .tag("outcome", "success")
                .timer();
        assertThat(approvals).isNotNull();
        assertThat(approvals.count()).isGreaterThanOrEqualTo(1);
        Timer rejections = meterRegistry
                .find(GatewayMetrics.APPROVAL)
                .tag("decision", "reject")
                .tag("outcome", "success")
                .timer();
        assertThat(rejections).isNotNull();
        assertThat(rejections.count()).isGreaterThanOrEqualTo(1);

        Counter uploads = meterRegistry
                .find(GatewayMetrics.FACADE_FETCHES)
                .tag("event", "upload-pack")
                .counter();
        Counter advertisements = meterRegistry
                .find(GatewayMetrics.FACADE_FETCHES)
                .tag("event", "info-refs")
                .counter();
        assertThat(uploads).isNotNull();
        assertThat(uploads.count()).isGreaterThanOrEqualTo(1);
        assertThat(advertisements).isNotNull();
        assertThat(advertisements.count()).isGreaterThanOrEqualTo(1);

        // The observation never changes the observed behavior: a vetting-blocked approval still
        // surfaces its refusal, and lands on the error side of the decision timer.
        Registered blocked = registerAndIngest(
                uniqueName("metricsblocked"),
                createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRETS)));
        assertThatThrownBy(() -> approvalService.approve(blocked.snapshot().id(), "alice"))
                .isInstanceOf(VettingBlockedException.class);
        Timer refused = meterRegistry
                .find(GatewayMetrics.APPROVAL)
                .tag("decision", "approve")
                .tag("outcome", "error")
                .timer();
        assertThat(refused).isNotNull();
        assertThat(refused.count()).isGreaterThanOrEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    /** One real clone through the facade, authenticated as {@code principal} via a fresh PAT. */
    private void clone(String marketplace, String principal) throws Exception {
        String pat = tokenService.create(principal, "adoption-test").token();
        GitResult result = gitClone(facadeUrl(marketplace, pat), newWorkDir("adoption-clone"));
        assertThat(result.exitCode()).as(result.output()).isZero();
    }

    private JsonNode adoption(String uri) throws Exception {
        String body = mockMvc.perform(get(uri).with(oidcLogin().idToken(token -> token.subject("auditor"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return MAPPER.readTree(body);
    }

    private static JsonNode marketplaceEntry(JsonNode report, String marketplace) {
        for (JsonNode entry : report) {
            if (marketplace.equals(entry.get("marketplace").asText())) {
                return entry;
            }
        }
        throw new AssertionError("marketplace %s not in report %s".formatted(marketplace, report));
    }

    /** The staleness entries concerning one marketplace; other suites' fixtures are not ours. */
    private List<JsonNode> stalenessOf(String marketplace) throws Exception {
        List<JsonNode> entries = new ArrayList<>();
        for (JsonNode entry : adoption("/api/adoption/staleness")) {
            if (marketplace.equals(entry.get("marketplace").asText())) {
                entries.add(entry);
            }
        }
        return entries;
    }
}

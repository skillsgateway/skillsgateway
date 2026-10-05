package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.admin.MarketplaceRegistrationService;
import dev.skillsgateway.server.admin.MarketplaceRemovalService;
import dev.skillsgateway.server.approval.FourEyesConflictException;
import dev.skillsgateway.server.approval.FourEyesGate;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository.ForgeMetadata;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Correcting an upstream marketplace's URL before its first snapshot (GW_INGEST_0066).
 *
 * <p>The URL is a registration decision, so most of this attacks the edit: every refusal must leave
 * the URL, the registrant and the ledger exactly as they were, and a refusal that needs no network
 * must not contact the upstream it names.
 */
class MarketplaceUrlChangeTests extends AbstractGatewayTest {

    private static final String EVENT = "marketplace-url-changed";

    private static GitHttpFixture forge;

    private final OidcLoginRequestPostProcessor root = oidcLogin().idToken(token -> token.subject("root"));

    private final OidcLoginRequestPostProcessor mallory = oidcLogin().idToken(token -> token.subject("mallory"));

    @Autowired
    private MarketplaceRegistrationService registrationService;

    @Autowired
    private MarketplaceRemovalService removalService;

    @Autowired
    private FourEyesGate fourEyesGate;

    @BeforeAll
    static void startForge() throws Exception {
        forge = new GitHttpFixture();
        forge.publish("acme/skills", Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
    }

    @AfterAll
    static void stopForge() {
        forge.close();
    }

    /** A marketplace registered with a typo, as a declared registration with an unreadable upstream is. */
    private Marketplace typoed(String name) {
        return marketplaceRepository.register(
                name,
                "file:///nowhere/" + name + "/typo.git",
                new ForgeMetadata("github", "acme/typo", "stale description", null),
                Marketplace.ORIGIN_UPSTREAM,
                Marketplace.PUSH_APPEND_ONLY,
                "alice");
    }

    private ResultActions change(String name, String url, OidcLoginRequestPostProcessor as) throws Exception {
        return mockMvc.perform(put("/api/v1/marketplaces/{name}/url", name)
                .with(as)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"%s\"}".formatted(url)));
    }

    private long ledgerRows(String name, String event) {
        return fetchLogRepository.list().stream()
                .filter(row -> name.equals(row.get("marketplace")) && event.equals(row.get("event")))
                .count();
    }

    /** What a refused change must leave behind. */
    private void assertUnchanged(Marketplace before) {
        Marketplace after = marketplaceRepository.findById(before.id()).orElseThrow();
        assertThat(after.url()).isEqualTo(before.url());
        assertThat(after.registeredBy()).isEqualTo(before.registeredBy());
        assertThat(after.forge()).isEqualTo(before.forge());
        assertThat(ledgerRows(before.name(), EVENT)).isZero();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0066"})
    void an_administrator_corrects_the_url_and_becomes_its_registrant() throws Exception {
        String name = uniqueName("urlfix");
        Marketplace before = typoed(name);
        // Over HTTP: the duplicate comparison normalizes URLs with a host, which a file URL lacks.
        String repo = "acme/" + name;
        forge.publish(repo, Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        String corrected = forge.baseUrl() + "/" + repo + ".git";
        String other = uniqueName("urlother");
        marketplaceRepository.register(other, corrected);

        change(name, corrected, root)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.url").value(corrected))
                .andExpect(jsonPath("$.registeredBy").value("root"))
                .andExpect(jsonPath("$.warnings", Matchers.hasItem("url already registered as " + other)))
                .andExpect(jsonPath("$.warnings", Matchers.not(Matchers.hasItem("url already registered as " + name))));

        Marketplace after = marketplaceRepository.findByName(name).orElseThrow();
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.url()).isEqualTo(corrected);
        assertThat(after.registeredBy()).isEqualTo("root");
        assertThat(after.forge())
                .as("forge metadata is resolved again for the new URL")
                .isNull();
        assertThat(after.description()).isNull();
        assertThat(fetchLogRepository.list())
                .filteredOn(row -> name.equals(row.get("marketplace")) && EVENT.equals(row.get("event")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("principal")).isEqualTo("root");
                    assertThat(String.valueOf(row.get("detail")))
                            .contains(before.url())
                            .contains(corrected);
                });

        // The typo'd URL is unreadable, so an ingest that succeeds has read the corrected one.
        assertThat(ingestionService.ingest(after, "ingrid").state()).isEqualTo(Snapshot.HELD);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0066"})
    void the_editor_and_not_the_original_registrant_is_kept_from_approving() throws Exception {
        String name = uniqueName("urlfoureyes");
        typoed(name);
        change(name, createUpstream(DEFAULT_MANIFEST).toUri().toString(), root).andExpect(status().isOk());
        Marketplace after = marketplaceRepository.findByName(name).orElseThrow();
        Snapshot snapshot = ingestionService.ingest(after, "ingrid");

        assertThat(fourEyesGate.conflicts(snapshot, after, List.of(), "root"))
                .containsExactly(new FourEyesConflictException.Conflict(FourEyesGate.ROLE_REGISTERED_BY, "root", null));
        assertThat(fourEyesGate.conflicts(snapshot, after, List.of(), "alice")).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0066"})
    void an_unchanged_url_changes_nothing_and_is_not_recorded() throws Exception {
        String name = uniqueName("urlsame");
        Marketplace before = typoed(name);

        change(name, before.url(), root)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredBy").value("alice"));

        assertUnchanged(before);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0066"})
    void a_url_registration_would_refuse_is_refused_and_changes_nothing() throws Exception {
        String name = uniqueName("urlbad");
        Marketplace before = typoed(name);

        change(name, "ftp://example.com/acme/skills.git", root).andExpect(status().isBadRequest());
        change(name, forge.baseUrl().replace("http://", "http://user:s3cret@") + "/acme/skills.git", root)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", Matchers.not(Matchers.containsString("s3cret"))));
        change(name, forge.baseUrl() + "/acme/missing.git", root)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason", Matchers.not(Matchers.emptyOrNullString())));
        change(name, "", root).andExpect(status().isBadRequest());

        assertUnchanged(before);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0066"})
    void a_caller_who_is_not_an_administrator_is_refused() throws Exception {
        String name = uniqueName("urlnonadmin");
        Marketplace before = typoed(name);

        change(name, createUpstream(DEFAULT_MANIFEST).toUri().toString(), mallory)
                .andExpect(status().isForbidden());

        assertUnchanged(before);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0066"})
    void a_marketplace_with_a_snapshot_keeps_its_url_and_the_new_upstream_is_never_contacted() throws Exception {
        String name = uniqueName("urlsnap");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST), "alice", null);
        snapshotRepository.decide(registered.snapshot().id(), Snapshot.REJECTED, "root");

        change(name, forge.baseUrl() + "/acme/after-snapshot.git", root).andExpect(status().isConflict());

        assertUnchanged(registered.marketplace());
        assertThat(forge.requestedPaths()).noneMatch(path -> path.contains("after-snapshot"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0066"})
    void a_hosted_marketplace_has_no_upstream_to_change() throws Exception {
        String name = uniqueName("urlhosted");
        Marketplace hosted = registrationService
                .register(name, null, Marketplace.ORIGIN_HOSTED, null, "alice")
                .marketplace();

        change(name, createUpstream(DEFAULT_MANIFEST).toUri().toString(), root).andExpect(status().isBadRequest());

        assertUnchanged(hosted);
        assertThat(marketplaceRepository.findById(hosted.id()).orElseThrow().origin())
                .isEqualTo(Marketplace.ORIGIN_HOSTED);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0066"})
    void a_removed_or_unknown_name_is_not_found() throws Exception {
        String name = uniqueName("urlremoved");
        Marketplace removed = typoed(name);
        removalService.remove(name, "registered by mistake", "root");
        String corrected = createUpstream(DEFAULT_MANIFEST).toUri().toString();

        change(name, corrected, root).andExpect(status().isNotFound());
        change(uniqueName("urlunknown"), corrected, root).andExpect(status().isNotFound());

        assertUnchanged(removed);
    }
}

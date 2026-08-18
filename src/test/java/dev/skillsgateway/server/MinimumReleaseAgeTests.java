package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.ReleaseAgeGate;
import dev.skillsgateway.server.approval.SnapshotTooYoungException;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * The cooling-off window in force (GW_0073) — its own Spring context, because the shared fixture
 * deliberately runs at the zero default, which is itself the assertion that an upgrade changes
 * nothing: every other test in the suite approves a snapshot the instant it is ingested.
 *
 * <p>Ageing a snapshot is done by moving its {@code created_at} back, which is exactly what the
 * gate reads. That is deliberate rather than convenient: it names, in the test, the one column the
 * control depends on, so a future change that starts deriving the age from anywhere else has to
 * come past these assertions.
 */
@TestPropertySource(properties = "skills-gateway.vetting.minimum-release-age=24h")
class MinimumReleaseAgeTests extends AbstractGatewayTest {

    private static final Duration MINIMUM = Duration.ofHours(24);

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ReleaseAgeGate releaseAgeGate;

    @Test
    @SVCs({"SVC_GW_0073"})
    void a_snapshot_the_gateway_saw_moments_ago_cannot_be_approved_and_nothing_is_published() throws Exception {
        String name = uniqueName("cooloff");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();

        assertThatThrownBy(() -> approvalService.approve(id, "alice"))
                .isInstanceOf(SnapshotTooYoungException.class)
                .hasMessageContaining(ReleaseAgeGate.CONFIG_KEY)
                .hasMessageContaining("minimum release age of 1d")
                .hasMessageContaining("becomes approvable in");

        // The refusal precedes the transition: the snapshot is untouched and the facade has
        // nothing to serve for this marketplace.
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.HELD);
        assertThat(gitClone(facadeUrl(name, newPat()), newWorkDir("clone")).exitCode())
                .as("nothing was published")
                .isNotZero();

        // And it is on the ledger: a window that turned an approval away has to be as visible as
        // one that let it through.
        assertThat(ledger(name, "snapshot-approval-refused")).singleElement().satisfies(detail -> assertThat(detail)
                .contains("minimum-release-age", "remaining="));
    }

    @Test
    @SVCs({"SVC_GW_0073"})
    void a_snapshot_that_has_cleared_the_window_is_approved_and_its_age_is_on_the_ledger() throws Exception {
        String name = uniqueName("cooloff");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();
        ageBy(id, MINIMUM.plusHours(4));

        mockMvc.perform(post("/api/snapshots/{id}/approve", id).with(oidcLogin()))
                .andExpect(status().isOk());

        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.APPROVED);
        assertThat(ledger(name, "snapshot-approved")).singleElement().isEqualTo("ingestion-age=1d 4h");
    }

    @Test
    @SVCs({"SVC_GW_0073"})
    void a_backdated_commit_does_not_buy_its_way_past_the_window() throws Exception {
        // The adversarial case the clock choice exists for: a committer date is written by whoever
        // made the commit, so an attacker defeating a cooling-off window would only have to claim
        // the commit is old. The gateway's own first sighting is what counts.
        String name = uniqueName("backdated");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Instant committed = backdateHead(upstream, Duration.ofDays(400));
        assertThat(committed).isBefore(Instant.now().minus(Duration.ofDays(399)));

        Registered registered = registerAndIngest(name, upstream);

        assertThatThrownBy(() -> approvalService.approve(registered.snapshot().id(), "alice"))
                .isInstanceOf(SnapshotTooYoungException.class);
        assertThat(releaseAgeGate.evaluate(registered.snapshot()).remainingSeconds())
                .isPositive();
    }

    @Test
    @SVCs({"SVC_GW_0073"})
    void re_ingesting_the_same_commit_does_not_restart_the_clock() throws Exception {
        String name = uniqueName("reingest");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(name, upstream);
        long id = registered.snapshot().id();
        ageBy(id, MINIMUM.plusHours(1));
        Instant firstSeen = snapshotRepository.findById(id).orElseThrow().createdAt();

        // Upstream pushes nothing new; the gateway ingests the same commit again.
        Snapshot again = ingestionService.ingest(registered.marketplace());

        assertThat(again.id()).isEqualTo(id);
        assertThat(snapshotRepository.findById(id).orElseThrow().createdAt()).isEqualTo(firstSeen);
        assertThat(releaseAgeGate.evaluate(again).eligible())
                .as("a re-push cannot take the window back to the start")
                .isTrue();
        assertThatCode(() -> approvalService.approve(id, "alice")).doesNotThrowAnyException();
    }

    @Test
    @SVCs({"SVC_GW_0073"})
    void rejection_is_never_gated_by_age() throws Exception {
        Registered registered = registerAndIngest(uniqueName("cooloff"), createUpstream(DEFAULT_MANIFEST));

        Snapshot rejected = approvalService.reject(registered.snapshot().id(), "alice");

        assertThat(rejected.state()).isEqualTo(Snapshot.REJECTED);
    }

    @Test
    @SVCs({"SVC_GW_0073"})
    void the_eligibility_endpoint_answers_what_the_gate_will_do() throws Exception {
        Registered registered = registerAndIngest(uniqueName("cooloff"), createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();

        mockMvc.perform(get("/api/snapshots/{id}/release-age", id).with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.minimumReleaseAgeSeconds").value((int) MINIMUM.toSeconds()))
                .andExpect(jsonPath("$.remainingSeconds").value(org.hamcrest.Matchers.greaterThan(0)));

        // The refusal a reviewer would have hit carries the same numbers the report showed.
        mockMvc.perform(post("/api/snapshots/{id}/approve", id).with(oidcLogin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Snapshot has not reached the minimum release age"))
                .andExpect(jsonPath("$.configKey").value(ReleaseAgeGate.CONFIG_KEY))
                .andExpect(jsonPath("$.eligibility.eligible").value(false));

        ageBy(id, MINIMUM);

        mockMvc.perform(get("/api/snapshots/{id}/release-age", id).with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.remainingSeconds").value(0));
    }

    /** Moves the gateway's first-sighting stamp back, which is all "older than the window" means. */
    private void ageBy(long snapshotId, Duration age) {
        jdbc.sql("UPDATE snapshots SET created_at = :when WHERE id = :id")
                .param("when", OffsetDateTime.ofInstant(Instant.now().minus(age), ZoneOffset.UTC))
                .param("id", snapshotId)
                .update();
    }

    /** The {@code detail} of every ledger entry of one marketplace for one event. */
    private List<String> ledger(String marketplace, String event) {
        return fetchLogRepository.list().stream()
                .filter(row -> marketplace.equals(row.get("marketplace")) && event.equals(row.get("event")))
                .map(row -> (String) row.get("detail"))
                .toList();
    }

    /** Rewrites the fixture's tip as a commit claiming to be {@code age} old. */
    private static Instant backdateHead(Path upstream, Duration age) throws IOException, GitAPIException {
        try (Git git = Git.open(upstream.toFile())) {
            Files.writeString(upstream.resolve("NOTES.md"), "backdated\n");
            git.add().addFilepattern(".").call();
            PersonIdent past =
                    new PersonIdent("Test", "test@example.com", Instant.now().minus(age), ZoneOffset.UTC);
            RevCommit commit = git.commit()
                    .setMessage("backdated " + uniqueName("commit"))
                    .setAuthor(past)
                    .setCommitter(past)
                    .setSign(false)
                    .call();
            return commit.getCommitterIdent().getWhenAsInstant();
        }
    }
}

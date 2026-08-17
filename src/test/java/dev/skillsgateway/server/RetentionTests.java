package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.retention.RetentionService;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Snapshot retention: criteria evaluation (GW_0031), soft deletion with a restore window
 * (GW_0032), the approved-snapshot guard (GW_0033), hard-delete compaction including the git
 * storage (GW_0034), and the ledger record of all of it (GW_0035).
 */
class RetentionTests extends AbstractGatewayTest {

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private GitStorage storage;

    /** Ingests the current upstream head as a new snapshot of the marketplace. */
    private Snapshot ingest(Marketplace marketplace) {
        return ingestionService.ingest(marketplace);
    }

    private boolean hasPin(String marketplace, String sha) throws IOException {
        try (Repository repository = storage.quarantine(marketplace)) {
            return repository.exactRef("refs/snapshots/" + sha) != null;
        }
    }

    private List<Map<String, Object>> ledgerFor(String marketplace) {
        return fetchLogRepository.list().stream()
                .filter(entry -> marketplace.equals(entry.get("marketplace")))
                .toList();
    }

    private static boolean hasEvent(List<Map<String, Object>> entries, String event, String sha) {
        return entries.stream()
                .anyMatch(entry -> event.equals(entry.get("event"))
                        && "alice".equals(entry.get("principal"))
                        && (sha == null || sha.equals(entry.get("sha"))));
    }

    @Test
    @SVCs({"SVC_GW_0031"})
    void policySelectsTheAgedAndSupersededSnapshotsAndNothingElse() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("aged");
        Registered registered = registerAndIngest(name, upstream);
        Marketplace marketplace = registered.marketplace();
        Snapshot held = registered.snapshot();

        addUpstreamCommit(upstream, "second");
        Snapshot rejected = ingest(marketplace);
        approvalService.reject(rejected.id(), "alice");

        addUpstreamCommit(upstream, "third");
        Snapshot approved = ingest(marketplace);
        approve(approved.id());

        // Held like the first, but fetched through the facade a moment ago: the last-served guard
        // vetoes it whatever criterion matched.
        addUpstreamCommit(upstream, "fourth");
        Snapshot recentlyServed = ingest(marketplace);
        fetchLogRepository.append("127.0.0.1", "alice", name, "fetch", "main", recentlyServed.sha());

        List<RetentionService.Candidate> candidates = retentionService.candidates(name);

        assertThat(candidates)
                .extracting(RetentionService.Candidate::snapshotId, RetentionService.Candidate::reason)
                .containsExactlyInAnyOrder(tuple(held.id(), "held-too-long"), tuple(rejected.id(), "superseded"));
        assertThat(candidates)
                .extracting(RetentionService.Candidate::snapshotId)
                .doesNotContain(approved.id(), recentlyServed.id());
    }

    /**
     * The last-served guard is about this marketplace's traffic. A SHA is not unique across
     * marketplaces — a fork, a mirror, or the same upstream registered twice all carry it — so a
     * fetch of another marketplace's identically-pinned snapshot must not veto this one, which
     * would otherwise hold it in quarantine for as long as the other marketplace stays in use.
     */
    @Test
    @SVCs({"SVC_GW_0031"})
    void aFetchOfAnotherMarketplaceSharingTheCommitDoesNotVetoSelection() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String mine = uniqueName("shared");
        String theirs = uniqueName("mirror");
        Registered registered = registerAndIngest(mine, upstream);
        Registered mirror = registerAndIngest(theirs, upstream);
        assertThat(mirror.snapshot().sha()).isEqualTo(registered.snapshot().sha());

        fetchLogRepository.append(
                "127.0.0.1",
                "bob",
                theirs,
                "upload-pack",
                "main",
                mirror.snapshot().sha());

        assertThat(retentionService.candidates(mine))
                .extracting(RetentionService.Candidate::snapshotId, RetentionService.Candidate::reason)
                .contains(tuple(registered.snapshot().id(), "held-too-long"));
    }

    @Test
    @SVCs({"SVC_GW_0032"})
    void deletionMarksTheSnapshotAndARestoreInsideTheWindowClearsIt() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(uniqueName("softdel"), upstream);
        long id = registered.snapshot().id();

        mockMvc.perform(delete("/api/snapshots/" + id).with(oidcLogin())).andExpect(status().isOk());

        Snapshot deleted = snapshotRepository.findById(id).orElseThrow();
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.deletedReason()).isEqualTo(RetentionService.MANUAL_REASON);
        assertThat(deleted.purgeAfter()).isAfter(Instant.now());
        // The vetting state is untouched by deletion: the snapshot was, and remains, held.
        assertThat(deleted.state()).isEqualTo(Snapshot.HELD);

        mockMvc.perform(post("/api/snapshots/%d/restore".formatted(id)).with(oidcLogin()))
                .andExpect(status().isOk());

        Snapshot restored = snapshotRepository.findById(id).orElseThrow();
        assertThat(restored.deleted()).isFalse();
        assertThat(restored.purgeAfter()).isNull();
        assertThat(restored.state()).isEqualTo(Snapshot.HELD);
    }

    @Test
    @SVCs({"SVC_GW_0033"})
    void anApprovedSnapshotIsNeverSelectedAndCannotBeDeleted() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("served");
        Registered registered = registerAndIngest(name, upstream);
        Snapshot served = approve(registered.snapshot().id());

        // A later approval supersedes it and its quarantine age is far past the threshold, so every
        // criterion would match were the approved state not categorically ineligible.
        addUpstreamCommit(upstream, "successor");
        approve(ingest(registered.marketplace()).id());

        assertThat(retentionService.candidates(name))
                .extracting(RetentionService.Candidate::snapshotId)
                .doesNotContain(served.id());

        mockMvc.perform(delete("/api/snapshots/" + served.id()).with(oidcLogin()))
                .andExpect(status().isConflict());

        retentionService.evaluate("alice", name);
        retentionService.compact("alice");

        assertThat(snapshotRepository.findById(served.id()).orElseThrow().deleted())
                .isFalse();
        // Still published: a clone of the facade repository succeeds.
        Path clone = newWorkDir("served-clone");
        assertThat(gitClone(facadeUrl(name, newPat()), clone.resolve("repo")).exitCode())
                .isZero();
    }

    @Test
    @SVCs({"SVC_GW_0034"})
    void compactionRemovesExpiredDeletionsAndTheirQuarantineReference() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("compact");
        Registered registered = registerAndIngest(name, upstream);
        Snapshot expired = registered.snapshot();
        addUpstreamCommit(upstream, "second");
        Snapshot inWindow = ingest(registered.marketplace());

        snapshotRepository.softDelete(
                expired.id(), "held-too-long", Instant.now().minus(1, ChronoUnit.MINUTES));
        snapshotRepository.softDelete(
                inWindow.id(), "held-too-long", Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(hasPin(name, expired.sha())).isTrue();

        retentionService.compact("alice");

        assertThat(snapshotRepository.findById(expired.id())).isEmpty();
        assertThat(hasPin(name, expired.sha())).isFalse();
        // The snapshot still inside its restore window keeps both its record and its git storage.
        assertThat(snapshotRepository.findById(inWindow.id()).orElseThrow().deleted())
                .isTrue();
        assertThat(hasPin(name, inWindow.sha())).isTrue();
    }

    @Test
    @SVCs({"SVC_GW_0035"})
    void theLedgerRecordsEveryRetentionAction() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("audited");
        Registered registered = registerAndIngest(name, upstream);
        Snapshot snapshot = registered.snapshot();

        RetentionService.PassResult pass = retentionService.evaluate("alice", name);
        assertThat(pass.acted()).isPositive();
        retentionService.restore(snapshot.id(), "alice");

        snapshotRepository.softDelete(
                snapshot.id(), "held-too-long", Instant.now().minus(1, ChronoUnit.MINUTES));
        retentionService.compact("alice");

        List<Map<String, Object>> entries = ledgerFor(name);
        assertThat(entries)
                .extracting(entry -> (String) entry.get("event"))
                .anyMatch(event -> event.startsWith("retention-evaluated:"));
        assertThat(hasEvent(entries, "snapshot-soft-deleted:held-too-long", snapshot.sha()))
                .isTrue();
        assertThat(hasEvent(entries, "snapshot-restored", snapshot.sha())).isTrue();
        assertThat(hasEvent(entries, "snapshot-purged", snapshot.sha())).isTrue();
    }
}

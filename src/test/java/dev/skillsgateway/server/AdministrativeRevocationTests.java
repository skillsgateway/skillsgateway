package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.approval.MissingReversalReasonException;
import dev.skillsgateway.server.approval.MissingRevocationReasonException;
import dev.skillsgateway.server.approval.NotApprovedException;
import dev.skillsgateway.server.approval.NothingToReverseException;
import dev.skillsgateway.server.approval.RevocationReversalRepository;
import dev.skillsgateway.server.approval.RevocationService;
import dev.skillsgateway.server.approval.RollbackUnavailableException;
import dev.skillsgateway.server.approval.SelfReversalException;
import dev.skillsgateway.server.approval.SnapshotWithdrawnException;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Administrative revocation (GW_APPROVAL_0015, GW_APPROVAL_0016, GW_APPROVAL_0017).
 *
 * <p>This is a trust boundary: it writes to the served set, and it adds a gate in front of
 * approval. So the tests here are mostly negative. The happy path — an administrator withdraws
 * something and it stops being served — is the easy half and the one least likely to be wrong; what
 * is worth attacking is whether the refusals actually refuse, whether a request that cannot be
 * honoured changes nothing, and whether a withdrawal can be undone by anyone acting alone.
 *
 * <p>Two administrators, because the point of the reversal rule is that one is not enough. They are
 * {@code root} and {@code alice} rather than names of this suite's own, so it shares the cached
 * context every other suite uses: a suite that declares its own properties is a whole extra Spring
 * context, and {@code ContextBudgetTests} is the ratchet that says so.
 *
 * <p>The admin-only half of the surface is verified where the project already verifies it, by
 * route, in {@code RoleEnforcementTests}.
 */
class AdministrativeRevocationTests extends AbstractGatewayTest {

    @Autowired
    private RevocationService revocationService;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Autowired
    private RevocationReversalRepository reversalRepository;

    @Autowired
    private GitStorage storage;

    /**
     * The act itself, and the two things that must not stop it happening (GW_APPROVAL_0015).
     *
     * <p>The chain clears this snapshot and re-vetting is in its default record-only mode. Both are
     * deliberate: an administrator withdraws on knowledge the chain does not hold, so a clearing
     * chain is the normal case rather than an edge one, and a mode that governs what automated
     * re-vetting does with its own findings has no business gating a person's decision.
     */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0015"})
    void an_administrator_withdraws_a_clean_snapshot_and_it_stops_being_served() throws Exception {
        Registered registered = registerAndIngest(uniqueName("adminrevoke"), createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();
        approve(id);
        assertThat(storage.publishedIfServing(registered.marketplace().name()))
                .as("the snapshot is served before it is withdrawn")
                .isPresent();

        RevocationService.Revoked revoked = revocationService.revoke(
                id, "CVE-2026-0001 in a vendored dependency", RevocationService.ServeAfter.NOTHING, "root");

        assertThat(revoked.snapshot().state()).isEqualTo(Snapshot.REVOKED);
        assertThat(revoked.snapshot().revokedBy()).isEqualTo("root");
        assertThat(revoked.snapshot().revokedKind()).isEqualTo(Snapshot.REVOKED_ADMINISTRATIVELY);
        assertThat(revoked.snapshot().violation()).isEqualTo("CVE-2026-0001 in a vendored dependency");
        assertThat(revoked.snapshot().revokedAt()).isNotNull();
        assertThat(storage.publishedIfServing(registered.marketplace().name()))
                .as("the facade no longer resolves the marketplace")
                .isEmpty();

        assertThat(ledgerEvents(registered.marketplace().name()))
                .contains(RevocationService.EVENT_REVOKED, RevocationService.EVENT_UNPUBLISHED);

        // Quarantine is untouched, so the decision stays reviewable and reversible by a person.
        try (var quarantine = storage.quarantine(registered.marketplace().name())) {
            assertThat(quarantine.exactRef(
                            "refs/snapshots/" + revoked.snapshot().sha()))
                    .isNotNull();
        }
    }

    /** Each way a withdrawal is refused, and each one changing nothing (GW_APPROVAL_0015). */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0015"})
    void a_withdrawal_with_no_reason_or_of_something_not_approved_is_refused() throws Exception {
        Registered registered = registerAndIngest(uniqueName("revokerefuse"), createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();

        // Held, not approved: nothing to withdraw.
        assertThatThrownBy(() -> revocationService.revoke(id, "a reason", RevocationService.ServeAfter.NOTHING, "root"))
                .isInstanceOf(NotApprovedException.class);

        approve(id);
        for (String reason : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> revocationService.revoke(id, reason, RevocationService.ServeAfter.NOTHING, "root"))
                    .as("a withdrawal with reason %s is refused", reason == null ? "null" : "'" + reason + "'")
                    .isInstanceOf(MissingRevocationReasonException.class);
        }
        assertThat(snapshotRepository.findById(id).orElseThrow().state())
                .as("a refused withdrawal leaves the snapshot approved")
                .isEqualTo(Snapshot.APPROVED);
        assertThat(storage.publishedIfServing(registered.marketplace().name()))
                .as("and leaves it served")
                .isPresent();

        // And a second withdrawal of something already withdrawn.
        revocationService.revoke(id, "withdrawn once", RevocationService.ServeAfter.NOTHING, "root");
        assertThatThrownBy(() -> revocationService.revoke(id, "again", RevocationService.ServeAfter.NOTHING, "root"))
                .isInstanceOf(NotApprovedException.class);
    }

    /**
     * Two administrators withdrawing the same snapshot at once (GW_APPROVAL_0015).
     *
     * <p>The transition carries {@code state = 'approved'} in its own statement, so exactly one
     * caller can win. The thing worth asserting is not that one throws — it is that the content is
     * unpublished once, because a second unpublish of an already-withdrawn snapshot is the failure
     * that would make a concurrent withdrawal look like a storage fault.
     */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0015"})
    void two_concurrent_withdrawals_leave_one_winner_and_unpublish_once() throws Exception {
        Registered registered = registerAndIngest(uniqueName("revokerace"), createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();
        approve(id);

        Callable<Object> attempt = () -> {
            try {
                return revocationService.revoke(id, "concurrent", RevocationService.ServeAfter.NOTHING, "root");
            } catch (RuntimeException e) {
                return e;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> results = pool.invokeAll(List.of(attempt, attempt));
            List<Object> outcomes = List.of(results.get(0).get(), results.get(1).get());
            assertThat(outcomes)
                    .as("exactly one withdrawal wins the transition")
                    .anySatisfy(o -> assertThat(o).isInstanceOf(RevocationService.Revoked.class))
                    .anySatisfy(o -> assertThat(o).isInstanceOf(NotApprovedException.class));
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledgerEvents(registered.marketplace().name()).stream()
                        .filter(RevocationService.EVENT_UNPUBLISHED::equals)
                        .count())
                .as("the content is unpublished exactly once")
                .isEqualTo(1);
    }

    /**
     * The served-content choice, from the side that matters (GW_APPROVAL_0016).
     *
     * <p>Asking to return to a previous approved snapshot when there is none must refuse
     * <em>and leave the snapshot approved</em>. Asserting only the exception would pass against an
     * implementation that withdraws first and discovers the problem afterwards — which would hand
     * the caller the serve-nothing outcome they explicitly did not choose.
     */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0016"})
    void a_return_with_nothing_to_return_to_is_refused_and_withdraws_nothing() throws Exception {
        Registered registered = registerAndIngest(uniqueName("norollback"), createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();
        approve(id);

        assertThatThrownBy(() -> revocationService.revoke(
                        id, "nothing to fall back to", RevocationService.ServeAfter.PREVIOUS_APPROVED, "root"))
                .isInstanceOf(RollbackUnavailableException.class);

        assertThat(snapshotRepository.findById(id).orElseThrow().state())
                .as("the snapshot is still approved: nothing was withdrawn")
                .isEqualTo(Snapshot.APPROVED);
        assertThat(storage.publishedIfServing(registered.marketplace().name()))
                .as("and the marketplace is still serving it")
                .isPresent();
        assertThat(ledgerEvents(registered.marketplace().name()))
                .as("and nothing was recorded")
                .doesNotContain(RevocationService.EVENT_REVOKED);
    }

    /** Returning to the previous approved snapshot, recorded as the publication it is (GW_APPROVAL_0016). */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0016"})
    void a_withdrawal_can_return_the_marketplace_to_its_previous_approved_snapshot() throws Exception {
        String name = uniqueName("rollback");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered first = registerAndIngest(name, upstream);
        approve(first.snapshot().id());

        // A second snapshot of the same marketplace, approved over the first.
        addUpstreamCommit(upstream, "second revision");
        Snapshot second = ingestionService.ingest(first.marketplace(), "alice");
        approve(second.id());
        assertThat(second.id()).isNotEqualTo(first.snapshot().id());

        RevocationService.Revoked revoked = revocationService.revoke(
                second.id(), "the new one is poisoned", RevocationService.ServeAfter.PREVIOUS_APPROVED, "root");

        assertThat(revoked.rolledBackTo()).isPresent();
        assertThat(revoked.rolledBackTo().orElseThrow().id())
                .isEqualTo(first.snapshot().id());
        assertThat(storage.publishedIfServing(name))
                .as("the marketplace is serving again, not dark")
                .isPresent();
        assertThat(ledgerEvents(name))
                .as("the return is its own act on the ledger, not a detail of the withdrawal")
                .contains(RevocationService.EVENT_ROLLED_BACK);
        assertThat(snapshotRepository
                        .findById(first.snapshot().id())
                        .orElseThrow()
                        .state())
                .as("the snapshot returned to service keeps the approval it already had")
                .isEqualTo(Snapshot.APPROVED);
    }

    /** Withdrawing something that is not the served snapshot leaves the served set alone (GW_APPROVAL_0016). */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0016"})
    void withdrawing_a_snapshot_that_is_not_the_served_one_changes_nothing_served() throws Exception {
        String name = uniqueName("notserved");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered first = registerAndIngest(name, upstream);
        approve(first.snapshot().id());
        addUpstreamCommit(upstream, "second revision");
        Snapshot second = ingestionService.ingest(first.marketplace(), "alice");
        approve(second.id());

        String servedBefore = headOfPublished(name);
        revocationService.revoke(
                first.snapshot().id(), "the older one was bad too", RevocationService.ServeAfter.NOTHING, "root");

        assertThat(headOfPublished(name))
                .as("the marketplace still serves what it served")
                .isEqualTo(servedBefore);
    }

    /**
     * The gate (GW_APPROVAL_0017): an ordinary approval of a withdrawn commit is refused, and the
     * refusal says who withdrew it, when and why.
     *
     * <p>Also that a waiver does not lift it. That is the confusion most likely to bite a reviewer:
     * the mechanism that lifts a re-vetting revocation looks like it should work here, and it must
     * not, because there is no finding to waive — the chain clears this content.
     */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0017"})
    void an_ordinary_approval_of_a_withdrawn_commit_is_refused_and_names_the_withdrawal() throws Exception {
        Registered registered = registerAndIngest(uniqueName("withdrawngate"), createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();
        approve(id);
        revocationService.revoke(id, "maintainer account compromised", RevocationService.ServeAfter.NOTHING, "root");

        assertThatThrownBy(() -> approvalService.approve(id, "alice"))
                .isInstanceOf(SnapshotWithdrawnException.class)
                .satisfies(e -> {
                    SnapshotWithdrawnException withdrawn = (SnapshotWithdrawnException) e;
                    assertThat(withdrawn.revokedBy()).isEqualTo("root");
                    assertThat(withdrawn.reason()).isEqualTo("maintainer account compromised");
                    assertThat(withdrawn.revokedAt()).isNotNull();
                });

        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.REVOKED);
        assertThat(storage.publishedIfServing(registered.marketplace().name()))
                .as("and nothing is published by a refused approval")
                .isEmpty();
        assertThat(ledgerEvents(registered.marketplace().name()))
                .as("a gate that turns approvals away silently cannot be audited")
                .contains(ApprovalService.EVENT_REFUSED_STANDING_REVOCATION);
    }

    /**
     * Reversal (GW_APPROVAL_0017): not the revoker, not without a reason, not when there is nothing
     * to reverse — and when a second administrator does it, the episode stays visible.
     */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0017"})
    void a_withdrawal_is_reversed_only_by_a_second_administrator_and_leaves_a_marker() throws Exception {
        Registered registered = registerAndIngest(uniqueName("reversal"), createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();
        approve(id);
        revocationService.revoke(id, "thought it was the bad one", RevocationService.ServeAfter.NOTHING, "root");

        // The administrator who withdrew it cannot put it back alone.
        assertThatThrownBy(() -> approvalService.approve(
                        id, "root", ApprovalService.ApprovalOverride.ofRevocationReversal("I was wrong")))
                .isInstanceOf(SelfReversalException.class);
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.REVOKED);

        // Nor can a second administrator without saying why.
        for (String reason : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> approvalService.approve(
                            id, "alice", ApprovalService.ApprovalOverride.ofRevocationReversal(reason)))
                    .isInstanceOf(MissingReversalReasonException.class);
        }

        ApprovalService.Approved approved = approvalService.approve(
                id, "alice", ApprovalService.ApprovalOverride.ofRevocationReversal("upstream fixed it; re-reviewed"));

        assertThat(approved.snapshot().state()).isEqualTo(Snapshot.APPROVED);
        assertThat(approved.snapshot().revokedKind())
                .as("the approval clears the withdrawal stamps")
                .isNull();
        assertThat(storage.publishedIfServing(registered.marketplace().name()))
                .as("and the content is served again")
                .isPresent();

        assertThat(approved.revocationReversal()).isNotNull();
        assertThat(reversalRepository.findBySnapshot(id))
                .as("the episode survives the approval that cleared the stamps")
                .hasValueSatisfying(marker -> {
                    assertThat(marker.revokedBy()).isEqualTo("root");
                    assertThat(marker.revocationReason()).isEqualTo("thought it was the bad one");
                    assertThat(marker.reversedBy()).isEqualTo("alice");
                    assertThat(marker.reason()).isEqualTo("upstream fixed it; re-reviewed");
                });
        assertThat(ledgerEvents(registered.marketplace().name())).contains(ApprovalService.EVENT_REVOCATION_REVERSED);
    }

    /** A reversal of a withdrawal that is not there is refused rather than ignored (GW_APPROVAL_0017). */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0017"})
    void reversing_a_withdrawal_that_does_not_exist_is_refused() throws Exception {
        Registered registered = registerAndIngest(uniqueName("noreversal"), createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();

        assertThatThrownBy(() -> approvalService.approve(
                        id, "alice", ApprovalService.ApprovalOverride.ofRevocationReversal("nothing to undo")))
                .isInstanceOf(NothingToReverseException.class);
        assertThat(snapshotRepository.findById(id).orElseThrow().state())
                .as("a refused reversal approves nothing")
                .isEqualTo(Snapshot.HELD);
    }

    /**
     * A re-vetting revocation is untouched by any of this (GW_VETTING_0013, GW_APPROVAL_0017).
     *
     * <p>The two kinds exist precisely so that this keeps working: a chain verdict is lifted by
     * clearing its finding, and it needs no second administrator and no acknowledgement.
     */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0017"})
    void a_re_vetting_revocation_still_lifts_by_an_ordinary_approval() throws Exception {
        Registered registered = registerAndIngest(uniqueName("revetkind"), createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();
        approve(id);
        snapshotRepository.revoke(id, "sweep", "re-vetting found something", Snapshot.REVOKED_BY_REVET);

        Snapshot reapproved = approvalService.approve(id, "alice").snapshot();

        assertThat(reapproved.state())
                .as("a chain verdict needs no reversal and no second administrator")
                .isEqualTo(Snapshot.APPROVED);
        assertThat(reversalRepository.findBySnapshot(id))
                .as("and leaves no reversal marker, because nothing was reversed")
                .isEmpty();
    }

    /**
     * The retention guard (GW_RETENTION_0008), and the reason it exists.
     *
     * <p>The refusal that keeps a withdrawn commit from being approved again is derived from the
     * snapshot row, which makes it exactly as durable as that row. Retention may already remove a
     * revoked snapshot permanently, so without this the withdrawal would expire on a timer nobody
     * connected to it — and the failure would be silent, because a purged row leaves nothing to
     * refuse with and nothing to record.
     *
     * <p>A re-vetting revocation is not retained, deliberately: it is a machine verdict a later run
     * can reach again from the content itself.
     */
    @Test
    @SVCs({"SVC_GW_RETENTION_0008"})
    void retention_reclaims_a_withdrawn_snapshot_but_never_erases_its_record() throws Exception {
        Registered withdrawn = registerAndIngest(uniqueName("retainadmin"), createUpstream(DEFAULT_MANIFEST));
        long adminId = withdrawn.snapshot().id();
        approve(adminId);
        revocationService.revoke(adminId, "supply-chain compromise", RevocationService.ServeAfter.NOTHING, "root");

        Registered byRevet = registerAndIngest(uniqueName("retainrevet"), createUpstream(DEFAULT_MANIFEST));
        long revetId = byRevet.snapshot().id();
        approve(revetId);
        snapshotRepository.revoke(revetId, "sweep", "a finding its waivers do not cover", Snapshot.REVOKED_BY_REVET);

        // Both are eligible to have their content reclaimed: that is the part worth reclaiming.
        assertThat(snapshotRepository.softDelete(
                        adminId, "retention", Instant.now().minusSeconds(1)))
                .as("an administratively withdrawn snapshot's content can still be reclaimed")
                .isPresent();
        assertThat(snapshotRepository.softDelete(
                        revetId, "retention", Instant.now().minusSeconds(1)))
                .isPresent();

        assertThat(snapshotRepository.purge(adminId))
                .as("but its record is never removed, because the record is the block")
                .isFalse();
        assertThat(snapshotRepository.purge(revetId))
                .as("while a chain verdict's record purges as before")
                .isTrue();

        assertThat(snapshotRepository.findById(adminId))
                .as("the withdrawal survives, so re-ingesting the same commit still finds it")
                .isPresent();
        assertThat(snapshotRepository.administrativeRevocationOf(
                        withdrawn.marketplace().id(), withdrawn.snapshot().sha()))
                .hasValueSatisfying(revocation -> {
                    assertThat(revocation.revokedBy()).isEqualTo("root");
                    assertThat(revocation.violation()).isEqualTo("supply-chain compromise");
                });
        assertThat(snapshotRepository.findById(revetId)).isEmpty();
    }

    /**
     * The declarative estate does not revoke (#65). A revocation is an act, not converge-able
     * state — the line ADR 0014 drew for exports — so {@code skills-gateway.estate.*} gains nothing
     * here and reconciliation must never withdraw anything.
     *
     * <p>Asserted structurally rather than behaviourally. The reconciler has no snapshot write path
     * at all, so driving it and observing that nothing was withdrawn would be testing the absence
     * of code, and would keep passing if somebody added one. What catches that is the dependency:
     * the day {@code EstateReconciler} is handed the means to revoke is the day this fails, which
     * is the moment the obligation needs re-arguing.
     */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0015"})
    void the_estate_reconciler_is_not_given_the_means_to_withdraw_content() {
        List<Class<?>> dependencies = java.util.Arrays.stream(
                        dev.skillsgateway.server.estate.EstateReconciler.class.getDeclaredConstructors())
                .flatMap(constructor -> java.util.Arrays.stream(constructor.getParameterTypes()))
                .toList();

        assertThat(dependencies)
                .as("a reconciler that cannot reach revocation cannot retract content by accident")
                .doesNotContain(RevocationService.class)
                .doesNotContain(SnapshotRepository.class);
    }

    private List<String> ledgerEvents(String marketplace) {
        return fetchLogRepository.list().stream()
                .filter(row -> marketplace.equals(row.get("marketplace")))
                .map(row -> String.valueOf(row.get("event")))
                .toList();
    }

    private String headOfPublished(String marketplace) throws IOException {
        try (var published = storage.publishedIfServing(marketplace).orElseThrow()) {
            return published.resolve("refs/heads/main").name();
        }
    }
}

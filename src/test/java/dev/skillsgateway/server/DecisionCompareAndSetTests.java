package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;

/**
 * A decision lands only on the snapshot state it was made about (GW_APPROVAL_0031). The gates and the
 * reviewer judged one row; if that row has since been withdrawn, approved or rejected by someone
 * else, the decision is refused rather than written over the change.
 */
class DecisionCompareAndSetTests extends AbstractGatewayTest {

    private Snapshot approvedSnapshot(String prefix) throws Exception {
        Registered registered = registerAndIngest(uniqueName(prefix), createUpstream(DEFAULT_MANIFEST));
        return approve(registered.snapshot().id());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0032"})
    void an_approval_of_a_snapshot_withdrawn_since_it_was_read_is_refused_and_the_withdrawal_stands() throws Exception {
        Snapshot approved = approvedSnapshot("casrevoke");
        Snapshot readByTheApprover = snapshotRepository.findById(approved.id()).orElseThrow();

        snapshotRepository.revoke(approved.id(), "root", "withdrawn by an administrator", "administrative");

        assertThatThrownBy(() -> snapshotRepository.decide(readByTheApprover, Snapshot.APPROVED, "approver-p"))
                .isInstanceOf(IllegalStateException.class);
        Snapshot after = snapshotRepository.findById(approved.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(Snapshot.REVOKED);
        assertThat(after.revokedKind()).isEqualTo("administrative");
        assertThat(after.revokedBy()).isEqualTo("root");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0032"})
    void a_rejection_of_a_snapshot_approved_since_it_was_read_is_refused_and_the_approval_stands() throws Exception {
        Registered registered = registerAndIngest(uniqueName("casreject"), createUpstream(DEFAULT_MANIFEST));
        Snapshot readByTheRejecter =
                snapshotRepository.findById(registered.snapshot().id()).orElseThrow();
        approve(registered.snapshot().id());

        assertThatThrownBy(() -> snapshotRepository.decide(readByTheRejecter, Snapshot.REJECTED, "approver-q"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(snapshotRepository
                        .findById(registered.snapshot().id())
                        .orElseThrow()
                        .state())
                .isEqualTo(Snapshot.APPROVED);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0032"})
    void an_approval_read_before_a_later_withdrawal_of_the_same_state_is_refused() throws Exception {
        Snapshot approved = approvedSnapshot("casrestamp");
        snapshotRepository.revoke(approved.id(), "revet", "a finding appeared", "revet");
        Snapshot readByTheApprover = snapshotRepository.findById(approved.id()).orElseThrow();

        // Meanwhile another approver re-approves it, and an administrator then withdraws it: the row
        // is revoked again, as it was when it was read, but by a different decision.
        snapshotRepository.decide(readByTheApprover, Snapshot.APPROVED, "approver-s");
        snapshotRepository.revoke(approved.id(), "root", "withdrawn by an administrator", "administrative");

        assertThatThrownBy(() -> snapshotRepository.decide(readByTheApprover, Snapshot.APPROVED, "approver-p"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(snapshotRepository.findById(approved.id()).orElseThrow().revokedKind())
                .isEqualTo("administrative");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0032"})
    void a_decision_on_the_state_it_read_still_lands() throws Exception {
        Registered registered = registerAndIngest(uniqueName("casok"), createUpstream(DEFAULT_MANIFEST));
        Snapshot held = snapshotRepository.findById(registered.snapshot().id()).orElseThrow();

        Snapshot rejected = snapshotRepository.decide(held, Snapshot.REJECTED, "approver-r");

        assertThat(rejected.state()).isEqualTo(Snapshot.REJECTED);
        assertThat(rejected.decidedBy()).isEqualTo("approver-r");
    }
}

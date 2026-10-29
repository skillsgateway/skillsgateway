package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.retention.RetentionService;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A soft-deleted snapshot cannot be approved or rejected until it is restored (GW_APPROVAL_0034):
 * deletion takes it out of every estate the gateway re-vets, withdraws and compares, so a decision
 * on it would publish content those never see.
 */
class DeletedSnapshotDecisionTests extends AbstractGatewayTest {

    @Autowired
    private RetentionService retentionService;

    private long deletedHeld() throws Exception {
        long id = registerAndIngest(uniqueName("deleted"), createUpstream(DEFAULT_MANIFEST))
                .snapshot()
                .id();
        retentionService.softDelete(id, RetentionService.MANUAL_REASON, "root");
        return id;
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0035"})
    void a_deleted_snapshot_is_neither_approved_nor_rejected() throws Exception {
        long approving = deletedHeld();
        long rejecting = deletedHeld();

        assertThatThrownBy(() -> approvalService.approve(approving, "alice")).hasMessageContaining("restore it");
        assertThatThrownBy(() -> approvalService.reject(rejecting, "alice")).hasMessageContaining("restore it");

        for (long id : new long[] {approving, rejecting}) {
            Snapshot snapshot = snapshotRepository.findById(id).orElseThrow();
            assertThat(snapshot.state()).as("snapshot %d", id).isEqualTo(Snapshot.HELD);
            assertThat(snapshot.deletedAt()).as("snapshot %d", id).isNotNull();
        }
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0035"})
    void a_decision_read_before_a_concurrent_delete_is_refused() throws Exception {
        long id = registerAndIngest(uniqueName("racing"), createUpstream(DEFAULT_MANIFEST))
                .snapshot()
                .id();
        Snapshot readBeforeDelete = snapshotRepository.findById(id).orElseThrow();
        retentionService.softDelete(id, RetentionService.MANUAL_REASON, "root");

        assertThatThrownBy(() -> snapshotRepository.decide(readBeforeDelete, Snapshot.APPROVED, "alice"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.HELD);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0035"})
    void a_restored_snapshot_is_decided_as_before() throws Exception {
        long id = deletedHeld();
        retentionService.restore(id, "root");

        assertThat(approve(id).state()).isEqualTo(Snapshot.APPROVED);
    }
}

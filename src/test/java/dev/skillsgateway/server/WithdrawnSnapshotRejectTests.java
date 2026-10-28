package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.approval.RevocationService;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A snapshot an administrator withdrew stays withdrawn under an ordinary reject (GW_APPROVAL_0035):
 * the withdrawal is what refuses re-approval of that commit and what retention keeps, and a reject
 * would have replaced it with a state that holds neither.
 */
class WithdrawnSnapshotRejectTests extends AbstractGatewayTest {

    @Autowired
    private RevocationService revocationService;

    private long approved() throws Exception {
        long id = registerAndIngest(uniqueName("withdrawn"), createUpstream(DEFAULT_MANIFEST))
                .snapshot()
                .id();
        approve(id);
        return id;
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0036"})
    void an_administratively_withdrawn_snapshot_is_not_rejected() throws Exception {
        long id = approved();
        revocationService.revoke(id, "withdrawn pending review", RevocationService.ServeAfter.NOTHING, "root");

        assertThatThrownBy(() -> approvalService.reject(id, "alice")).hasMessageContaining("administrator");

        Snapshot snapshot = snapshotRepository.findById(id).orElseThrow();
        assertThat(snapshot.state()).isEqualTo(Snapshot.REVOKED);
        assertThat(snapshot.revokedKind()).isEqualTo(Snapshot.REVOKED_ADMINISTRATIVELY);
        assertThat(snapshot.revokedBy()).isEqualTo("root");
        assertThat(snapshotRepository.administrativeRevocationOf(snapshot.marketplaceId(), snapshot.sha()))
                .as("the withdrawal still stands against the commit")
                .isPresent();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0036"})
    void a_snapshot_revoked_by_re_vetting_is_still_rejected() throws Exception {
        long id = approved();
        snapshotRepository.revoke(id, "re-vetting", "a finding now blocks", Snapshot.REVOKED_BY_REVET);

        assertThat(approvalService.reject(id, "alice").state()).isEqualTo(Snapshot.REJECTED);
    }
}

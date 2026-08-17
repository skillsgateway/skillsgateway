package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ApprovalTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0005"})
    void approvingHeldSnapshotRecordsReviewerAndTimestamp() throws Exception {
        Registered registered = registerAndIngest(uniqueName("corp"), createUpstream(DEFAULT_MANIFEST));

        Snapshot approved =
                approvalService.approve(registered.snapshot().id(), "alice").snapshot();

        assertThat(approved.state()).isEqualTo(Snapshot.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("alice");
        assertThat(approved.decidedAt()).isNotNull();
    }

    @Test
    @SVCs({"SVC_GW_0009"})
    void provenanceOfApprovedSnapshotIsRetrievable() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(uniqueName("corp"), upstream);
        approve(registered.snapshot().id());

        ApprovalService.Provenance provenance =
                approvalService.provenance(registered.snapshot().id()).orElseThrow();

        assertThat(provenance.upstreamUrl()).isEqualTo(upstream.toAbsolutePath().toString());
        assertThat(provenance.upstreamSha()).isEqualTo(registered.snapshot().sha());
        assertThat(provenance.decidedBy()).isEqualTo("alice");
        assertThat(provenance.decidedAt()).isNotNull();
    }
}

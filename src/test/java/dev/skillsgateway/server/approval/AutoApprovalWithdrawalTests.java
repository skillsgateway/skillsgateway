package dev.skillsgateway.server.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.skillsgateway.server.approval.RevocationService.ServeAfter;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.ServedTip;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The bulk withdrawal's race, which an end-to-end test cannot arrange (GW_APPROVAL_0044.14). */
class AutoApprovalWithdrawalTests {

    private final SnapshotRepository snapshots = mock(SnapshotRepository.class);
    private final MarketplaceRepository marketplaces = mock(MarketplaceRepository.class);
    private final ServedTip servedTip = mock(ServedTip.class);
    private final RevocationService revocation = mock(RevocationService.class);
    private final AutoApprovalSettingService settings = mock(AutoApprovalSettingService.class);
    private final AutoApprovalWithdrawal withdrawal =
            new AutoApprovalWithdrawal(snapshots, marketplaces, servedTip, revocation, settings);

    private static Snapshot approved(long id, String sha) {
        return new Snapshot(
                id,
                1L,
                sha,
                sha,
                Snapshot.APPROVED,
                null,
                Instant.now(),
                null,
                AutoApprovalService.ACTOR,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.14"})
    void aSnapshotWithdrawnMeanwhileIsSkippedAndTheRestAreStillWithdrawn() {
        Snapshot newer = approved(2L, "b".repeat(40));
        Snapshot older = approved(1L, "a".repeat(40));
        Marketplace marketplace = mock(Marketplace.class);
        when(marketplace.name()).thenReturn("acme");
        when(marketplaces.findById(1L)).thenReturn(Optional.of(marketplace));
        when(snapshots.approvedBySince(eq(AutoApprovalService.ACTOR), any())).thenReturn(List.of(newer, older));
        when(servedTip.of("acme")).thenReturn(Optional.empty());
        when(revocation.revoke(eq(2L), anyString(), any(), anyString(), any()))
                .thenThrow(new NotApprovedException(2L, Snapshot.REVOKED));
        Snapshot revoked = approved(1L, older.sha());
        when(revocation.revoke(eq(1L), anyString(), eq(ServeAfter.NOTHING), anyString(), any()))
                .thenReturn(new RevocationService.Revoked(revoked, false, Optional.empty()));
        when(snapshots.findByMarketplaceAndSha(anyLong(), anyString())).thenReturn(Optional.empty());

        AutoApprovalWithdrawal.WithdrawResponse response = withdrawal.withdraw(
                new AutoApprovalWithdrawal.WithdrawRequest(Instant.EPOCH, "a bad push", ServeAfter.NOTHING), "root");

        assertThat(response.withdrawn())
                .singleElement()
                .satisfies(entry -> assertThat(entry.snapshot().id()).isEqualTo(1L));
    }
}

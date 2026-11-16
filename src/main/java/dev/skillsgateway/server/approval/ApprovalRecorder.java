package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.vetting.WaiverService;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import org.springframework.stereotype.Service;

/**
 * Writes the evidence of an approval that {@link ApprovalService#approve} has already recorded:
 * the ledger entries and the webhook (GW_APPROVAL_0044.10).
 *
 * <p>One writer for every approval path, so a person's approval and an automatic one cannot drift
 * apart in what they leave behind. The webhook is enqueue-only: the caller never waits on, nor
 * fails because of, a receiver.
 */
@Service
public class ApprovalRecorder {

    private final AdminAuditLogger auditLogger;
    private final WaiverService waiverService;
    private final WebhookService webhookService;
    private final MarketplaceRepository marketplaceRepository;

    public ApprovalRecorder(
            AdminAuditLogger auditLogger,
            WaiverService waiverService,
            WebhookService webhookService,
            MarketplaceRepository marketplaceRepository) {
        this.auditLogger = auditLogger;
        this.waiverService = waiverService;
        this.webhookService = webhookService;
        this.marketplaceRepository = marketplaceRepository;
    }

    @Requirements({"GW_APPROVAL_0044.10"})
    public void recordApproved(ApprovalService.Approved approved, String actor) {
        Snapshot snapshot = approved.snapshot();
        String marketplace = marketplaceName(snapshot.marketplaceId());
        waiverService.recordUse(marketplace, snapshot.sha(), actor, approved.waiversApplied());
        // The override lands on the ledger as its own event beside snapshot-approved (GW_VETTING_0028), so
        // the decision entry says who approved and this one says what they overrode and why — both
        // facts kept, never one instead of the other.
        if (approved.vettingOverride() != null) {
            VettingOverrideRecord over = approved.vettingOverride();
            auditLogger.record(
                    actor,
                    marketplace,
                    ApprovalService.EVENT_OVERRIDE,
                    snapshot.sha(),
                    "reason=%s; blockingVetters=%s; uncovered=%s"
                            .formatted(over.reason(), over.blockingVetters(), over.uncoveredFindings()));
        }
        // The age at approval is on the decision's own ledger entry (GW_APPROVAL_0004.5): what the cooling-off
        // window was worth for this snapshot is only reconstructible if the entry says how long the
        // commit had been sitting in quarantine when someone adopted it.
        auditLogger.record(
                actor,
                marketplace,
                "snapshot-approved",
                snapshot.sha(),
                "ingestion-age=" + ReleaseAgeGate.format(approved.ingestionAge()));
        // The evidence this decision rested on was produced by a chain the marketplace no longer
        // runs (GW_VETTING_0038.1): enabling a vetter re-runs nothing, so a snapshot at the gate can carry
        // evidence the new vetter never produced. Recorded beside the approval, never instead of
        // it, and never as a refusal — the reviewer owns the decision, the ledger owns the fact.
        if (approved.chainStaleness() != null && approved.chainStaleness().stale()) {
            auditLogger.record(
                    actor,
                    marketplace,
                    ApprovalService.EVENT_APPROVED_ON_SUPERSEDED_CHAIN,
                    snapshot.sha(),
                    "evidenceChain=%s; chainInForce=%s"
                            .formatted(
                                    approved.chainStaleness().runChain(),
                                    approved.chainStaleness().currentChain()));
        }
        // Warn mode published a self-approval (GW_APPROVAL_0011). The decision entry above says who approved;
        // this one says why that was not an independent review, and it is written beside the
        // approval rather than instead of it so the ledger carries both facts.
        if (!approved.fourEyesConflicts().isEmpty()) {
            auditLogger.record(
                    actor,
                    marketplace,
                    FourEyesGate.EVENT_CONFLICT,
                    snapshot.sha(),
                    "mode=WARN, approved, conflicts=" + FourEyesGate.describe(approved.fourEyesConflicts()));
        }
        webhookService.emit(
                WebhookEvent.SNAPSHOT_APPROVED, marketplace, snapshot.id(), snapshot.sha(), snapshot.state(), actor);
    }

    private String marketplaceName(long marketplaceId) {
        return marketplaceRepository
                .findById(marketplaceId)
                .map(Marketplace::name)
                .orElse("unknown");
    }
}

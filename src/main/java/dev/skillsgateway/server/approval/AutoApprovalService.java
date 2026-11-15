package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.approval.ApprovalService.ApprovalOverride;
import dev.skillsgateway.server.approval.AutoApprovalSettingService.AllowedBy;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.config.SkillsGatewayProperties.AutoMode;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotClosureRepository;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.storage.ServedTip;
import dev.skillsgateway.server.vetting.HeldSnapshotChanged;
import dev.skillsgateway.server.vetting.Severity;
import dev.skillsgateway.server.vetting.SurfaceComparison;
import dev.skillsgateway.server.vetting.VettingRepository;
import dev.skillsgateway.server.vetting.VettingService;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Approves a marketplace's newest held snapshot with no person, when its source is internal and
 * nothing in it could run that did not run before (GW_APPROVAL_0044).
 *
 * <p>It decides only whether to ask; {@link ApprovalService#approve} still decides whether the
 * snapshot is published, with every gate a person's approval meets. Its actor is
 * {@value #ACTOR}, which the ledger types as a system actor.
 */
@Service
public class AutoApprovalService {

    private static final Logger log = LoggerFactory.getLogger(AutoApprovalService.class);

    public static final String ACTOR = "auto-approver";

    public static final String EVENT_APPROVED = "snapshot-auto-approved";
    public static final String EVENT_SKIPPED = "snapshot-auto-approval-skipped";
    public static final String EVENT_WOULD_APPROVE = "snapshot-auto-approval-would-approve";

    /** Stored as the skip reason in dry-run mode, so a repeated would-approve is not ledgered again. */
    static final String WOULD_APPROVE = "would-approve";

    private final SkillsGatewayProperties properties;
    private final MarketplaceRepository marketplaceRepository;
    private final SnapshotRepository snapshotRepository;
    private final SnapshotClosureRepository closureRepository;
    private final AutoApprovalSettingService settingService;
    private final AutoApprovalSettingRepository settingRepository;
    private final InternalSources internalSources;
    private final RequiredVetters requiredVetters;
    private final VettingService vettingService;
    private final WaiverService waiverService;
    private final ServedTip servedTip;
    private final GitStorage storage;
    private final ApprovalService approvalService;
    private final ApprovalRecorder recorder;
    private final AdminAuditLogger auditLogger;

    public AutoApprovalService(
            SkillsGatewayProperties properties,
            MarketplaceRepository marketplaceRepository,
            SnapshotRepository snapshotRepository,
            SnapshotClosureRepository closureRepository,
            AutoApprovalSettingService settingService,
            AutoApprovalSettingRepository settingRepository,
            InternalSources internalSources,
            RequiredVetters requiredVetters,
            VettingService vettingService,
            WaiverService waiverService,
            ServedTip servedTip,
            GitStorage storage,
            ApprovalService approvalService,
            ApprovalRecorder recorder,
            AdminAuditLogger auditLogger) {
        this.properties = properties;
        this.marketplaceRepository = marketplaceRepository;
        this.snapshotRepository = snapshotRepository;
        this.closureRepository = closureRepository;
        this.settingService = settingService;
        this.settingRepository = settingRepository;
        this.internalSources = internalSources;
        this.requiredVetters = requiredVetters;
        this.vettingService = vettingService;
        this.waiverService = waiverService;
        this.servedTip = servedTip;
        this.storage = storage;
        this.approvalService = approvalService;
        this.recorder = recorder;
        this.auditLogger = auditLogger;
    }

    @Schema(description = "What an automatic approval attempt would decide for a snapshot now")
    public record Evaluation(
            @Schema(
                    description = "The gateway's automatic approval mode",
                    allowableValues = {"off", "dry-run", "on"})
            String mode,

            @Schema(
                    description = "Why the marketplace's automatic approval takes effect, or none",
                    allowableValues = {"internal-source", "hosted", "exemption", "awaiting-second-administrator", "none"
                    })
            String allowedBy,

            @Schema(description = "Whether every check passes; under on, the approval gates then decide")
            boolean eligible,

            @Schema(description = "The first failing check, a stable code then a detail; null when eligible")
            String refusal,

            @Schema(description = "The served commit the surface was compared with, or null when nothing is served")
            String baselineSha,

            @Schema(description = "The worst severity among the run's findings, or null when it has none")
            Severity worstSeverity,

            @Schema(description = "The waivers suppressing a finding in the run")
            List<Long> waiverIds,

            @Schema(description = "The vetters switched off, which a relaxed required-vetters allowed")
            List<String> disabledVetters) {}

    /** The result of one attempt. */
    public enum Outcome {
        OFF,
        SKIPPED,
        WOULD_APPROVE,
        APPROVED
    }

    /** The same evaluation an attempt makes, in every mode, writing nothing (GW_APPROVAL_0044.12). */
    @Requirements({"GW_APPROVAL_0044.12"})
    public Optional<Evaluation> evaluate(long snapshotId) {
        return snapshotRepository
                .findById(snapshotId)
                .flatMap(snapshot -> marketplaceRepository
                        .findById(snapshot.marketplaceId())
                        .map(marketplace ->
                                evaluate(snapshot, marketplace, settingService.effectiveAllowedBy(marketplace))));
    }

    /**
     * One attempt, in the order of design D8: mode, re-vetting, what allows it, candidacy, vetting,
     * surface.
     * Every refusal leaves the snapshot held and is recorded once per distinct reason.
     */
    @Requirements({"GW_APPROVAL_0044", "GW_APPROVAL_0044.9", "GW_APPROVAL_0044.11", "GW_APPROVAL_0044.12"})
    public Outcome attempt(long snapshotId) {
        AutoMode mode = properties.approval().auto().mode();
        if (mode == AutoMode.OFF) {
            return Outcome.OFF;
        }
        Snapshot snapshot = snapshotRepository.findById(snapshotId).orElse(null);
        if (snapshot == null) {
            return Outcome.SKIPPED;
        }
        Marketplace marketplace =
                marketplaceRepository.findById(snapshot.marketplaceId()).orElse(null);
        if (marketplace == null) {
            return Outcome.SKIPPED;
        }
        AllowedBy allowedBy = settingService.resolveForAttempt(marketplace);
        Evaluation evaluation = evaluate(snapshot, marketplace, allowedBy);
        if (!evaluation.eligible()) {
            skip(snapshot, marketplace, evaluation.refusal());
            return Outcome.SKIPPED;
        }
        if (mode == AutoMode.DRY_RUN) {
            if (snapshotRepository.recordAutoApprovalSkip(snapshot.id(), WOULD_APPROVE)) {
                auditLogger.record(
                        ACTOR,
                        marketplace.name(),
                        EVENT_WOULD_APPROVE,
                        snapshot.sha(),
                        evidence(snapshot, marketplace, allowedBy, evaluation));
            }
            return Outcome.WOULD_APPROVE;
        }
        ApprovalService.Approved approved;
        try {
            approved = approvalService.approve(snapshot.id(), ACTOR, ApprovalOverride.none());
        } catch (RuntimeException refused) {
            String reason = gateRefusal(refused);
            if (refused instanceof IllegalStateException) {
                // A person decided first, so the row is no longer held and its stored reason cannot
                // change; a race happens once per snapshot, so it is ledgered without deduplication.
                auditLogger.record(ACTOR, marketplace.name(), EVENT_SKIPPED, snapshot.sha(), reason);
            } else {
                skip(snapshot, marketplace, reason);
            }
            return Outcome.SKIPPED;
        }
        recorder.recordApproved(approved, ACTOR);
        auditLogger.record(
                ACTOR,
                marketplace.name(),
                EVENT_APPROVED,
                snapshot.sha(),
                evidence(snapshot, marketplace, allowedBy, evaluation));
        return Outcome.APPROVED;
    }

    /** After an ingestion, a held snapshot's refreshed evidence, or a new waiver (design D10). */
    @TransactionalEventListener(fallbackExecution = true)
    public void onHeldSnapshotChanged(HeldSnapshotChanged changed) {
        attemptNewest(changed.marketplaceId(), changed.cause());
    }

    /** One bounded step on the re-vetting tick: each enabled marketplace's newest snapshot, once. */
    @EventListener
    @Requirements({"GW_APPROVAL_0044.9"})
    public void onRevetTick(HeldSnapshotChanged.RevetTick tick) {
        for (long marketplaceId : settingRepository.enabledMarketplaces()) {
            attemptNewest(marketplaceId, "revet-tick");
        }
    }

    /** After automatic approval is set (design D10). */
    public void attemptNewest(String marketplace, String cause) {
        marketplaceRepository.findByName(marketplace).ifPresent(found -> attemptNewest(found.id(), cause));
    }

    /** The marketplace's newest snapshot, if it is held: what every trigger attempts. */
    void attemptNewest(long marketplaceId, String cause) {
        if (properties.approval().auto().mode() == AutoMode.OFF) {
            return;
        }
        try {
            snapshotRepository
                    .newestLive(marketplaceId)
                    .filter(snapshot -> Snapshot.HELD.equals(snapshot.state()))
                    .ifPresent(snapshot -> attempt(snapshot.id()));
        } catch (RuntimeException e) {
            log.warn("automatic approval attempt for marketplace {} after {} failed", marketplaceId, cause, e);
        }
    }

    private Evaluation evaluate(Snapshot snapshot, Marketplace marketplace, AllowedBy allowedBy) {
        String mode = properties.approval().auto().mode().wire();
        String refusal = null;
        AutoApprovalEligibility.Vetting vetting = null;
        String baselineSha = servedTip.of(marketplace.name()).orElse(null);
        if (!properties.vetting().revet().enabled()) {
            refusal = "revet-disabled: re-vetting is off, so nothing would re-check what is approved";
        } else if (!allowedBy.effective()) {
            refusal = "not-allowed: " + allowedBy.wire();
        }
        if (refusal == null) {
            long newest = snapshotRepository
                    .newestLive(marketplace.id())
                    .map(Snapshot::id)
                    .orElse(snapshot.id());
            int members = closureRepository
                    .findBySnapshot(snapshot.id())
                    .map(closure -> closure.members().size())
                    .orElse(0);
            refusal = AutoApprovalEligibility.candidacy(snapshot, newest, members);
        }
        if (refusal == null) {
            VettingRepository.Run baselineRun = baselineSha == null
                    ? null
                    : snapshotRepository
                            .findByMarketplaceAndSha(marketplace.id(), baselineSha)
                            .flatMap(served -> vettingService.latestRun(served.id()))
                            .orElse(null);
            vetting = AutoApprovalEligibility.vetting(new AutoApprovalEligibility.VettingInputs(
                    vettingService.chainStaleness(snapshot).state(),
                    vettingService.latestRun(snapshot.id()).orElse(null),
                    waiverService.evaluate(snapshot),
                    baselineRun,
                    requiredVetters.names(),
                    requiredVetters.everyVetter()));
            refusal = vetting.refusal();
        }
        if (refusal == null) {
            refusal = surface(marketplace, baselineSha, snapshot.sha());
        }
        boolean eligible = refusal == null;
        return new Evaluation(
                mode,
                allowedBy.wire(),
                eligible,
                refusal,
                baselineSha,
                eligible ? vetting.worst() : null,
                eligible ? vetting.waiverIds() : List.of(),
                eligible ? vetting.disabledVetters() : List.of());
    }

    private String surface(Marketplace marketplace, String baselineSha, String sha) {
        try (Repository quarantine = storage.quarantine(marketplace.name())) {
            SurfaceComparison.Result result = SurfaceComparison.compare(
                    quarantine,
                    baselineSha == null ? null : ObjectId.fromString(baselineSha),
                    ObjectId.fromString(sha));
            return result.unchanged() ? null : "surface-changed: " + result.reason();
        } catch (IOException | RuntimeException e) {
            log.warn("Surface of {} at {} could not be compared; it stays held", marketplace.name(), sha, e);
            return "surface-unreadable: the surface could not be compared ("
                    + e.getClass().getSimpleName() + ")";
        }
    }

    private void skip(Snapshot snapshot, Marketplace marketplace, String reason) {
        if (snapshotRepository.recordAutoApprovalSkip(snapshot.id(), reason)) {
            auditLogger.record(ACTOR, marketplace.name(), EVENT_SKIPPED, snapshot.sha(), reason);
        }
    }

    /** A gate's refusal as a code that is the same on every attempt: never its message, which can name a time. */
    static String gateRefusal(RuntimeException refused) {
        if (refused instanceof IllegalStateException) {
            return "decided-elsewhere: the snapshot was decided before the automatic approval reached it";
        }
        String name = refused.getClass().getSimpleName().replaceFirst("Exception$", "");
        return "gate-refused: " + name.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private String evidence(Snapshot snapshot, Marketplace marketplace, AllowedBy allowedBy, Evaluation evaluation) {
        AutoApprovalSettingService.SettingView setting = settingService.view(marketplace.name());
        String named = switch (allowedBy) {
            case INTERNAL_SOURCE ->
                "internal-source "
                        + internalSources.matchingPrefix(marketplace.url()).orElse("?");
            case EXEMPTION ->
                "exemption "
                        + (setting.exemption() == null
                                ? "?"
                                : setting.exemption().id());
            default -> allowedBy.wire();
        };
        return "allowedBy=%s; grantor=%s; chain=%s; baseline=%s; worst=%s; waivers=%s; disabledVetters=%s"
                .formatted(
                        named,
                        setting.setBy(),
                        vettingService.chainStaleness(snapshot).runChain(),
                        evaluation.baselineSha() == null ? "none" : evaluation.baselineSha(),
                        evaluation.worstSeverity() == null ? "none" : evaluation.worstSeverity(),
                        evaluation.waiverIds(),
                        evaluation.disabledVetters());
    }
}

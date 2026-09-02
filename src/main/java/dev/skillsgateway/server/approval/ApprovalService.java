package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.catalog.CatalogService;
import dev.skillsgateway.server.observability.GatewayMetrics;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.policy.PolicyGate;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.vetting.WaiverEvaluation;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final GitStorage storage;
    private final SnapshotRepository snapshotRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final WaiverService waiverService;
    private final PolicyGate policyGate;
    private final CatalogService catalogService;
    private final GatewayMetrics metrics;
    private final ReleaseAgeGate releaseAgeGate;
    private final FourEyesGate fourEyesGate;
    private final AdminAuditLogger auditLogger;
    private final VettingOverrideRepository vettingOverrideRepository;

    public ApprovalService(
            GitStorage storage,
            SnapshotRepository snapshotRepository,
            MarketplaceRepository marketplaceRepository,
            WaiverService waiverService,
            PolicyGate policyGate,
            CatalogService catalogService,
            GatewayMetrics metrics,
            ReleaseAgeGate releaseAgeGate,
            FourEyesGate fourEyesGate,
            AdminAuditLogger auditLogger,
            VettingOverrideRepository vettingOverrideRepository) {
        this.storage = storage;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.waiverService = waiverService;
        this.policyGate = policyGate;
        this.catalogService = catalogService;
        this.metrics = metrics;
        this.releaseAgeGate = releaseAgeGate;
        this.fourEyesGate = fourEyesGate;
        this.auditLogger = auditLogger;
        this.vettingOverrideRepository = vettingOverrideRepository;
    }

    /** Ledger event for an approval the cooling-off window refused (GW_0073). */
    static final String EVENT_REFUSED = "snapshot-approval-refused";

    /**
     * Ledger event for an administrator approving a snapshot over a blocked vetting outcome
     * (GW_0148) — deliberately its own event so an override is never indistinguishable from a clean
     * approval on the ledger.
     */
    public static final String EVENT_OVERRIDE = "snapshot-approved-over-vetting-failure";

    /**
     * An administrator's request to approve past a blocked vetting outcome (GW_0148). The captain
     * disconnecting the autopilot: it lifts only the vetting gate, requires a reason, and is
     * admin-only (enforced at the controller). {@link #none()} is the ordinary approval, which
     * overrides nothing.
     */
    public record ApprovalOverride(boolean vettingFailure, String reason) {

        private static final ApprovalOverride NONE = new ApprovalOverride(false, null);

        public static ApprovalOverride none() {
            return NONE;
        }

        public static ApprovalOverride ofVettingFailure(String reason) {
            return new ApprovalOverride(true, reason);
        }

        public boolean hasReason() {
            return reason != null && !reason.isBlank();
        }
    }

    /**
     * An approved snapshot, the waivers that were in force when the gate let it through — empty for
     * a snapshot the chain cleared on its own merits (GW_0048) — and how long ago the gateway first
     * ingested its commit, which the ledger records against the decision (GW_0073).
     */
    public record Approved(
            Snapshot snapshot,
            List<WaiverEvaluation.Suppression> waiversApplied,
            Duration ingestionAge,
            List<FourEyesConflictException.Conflict> fourEyesConflicts,

            /** The administrator's override of a blocked vetting outcome, or null (GW_0148). */
            VettingOverrideRecord vettingOverride) {

        public Approved {
            fourEyesConflicts = fourEyesConflicts == null ? List.of() : List.copyOf(fourEyesConflicts);
        }
    }

    /**
     * Records the decision, then copies the pinned commit to published and advances main.
     *
     * <p>The vetting gate comes first and fails closed (GW_0041): a snapshot whose <em>effective</em>
     * outcome is blocked — its latest chain run objects and at least one blocking finding is not
     * covered by an active waiver, including the case of a snapshot with no chain run at all — is
     * refused. The check precedes the state transition, so a refused approval leaves the snapshot
     * held and publishes nothing.
     *
     * <p>There is no blanket override. Approving past objecting connectors means recording a scoped,
     * expiring waiver for each blocking finding first, so the reviewer accepts exactly what was
     * found and nothing else. The waivers that were in force are returned to the caller, which
     * appends them to the ledger as the acting identity.
     *
     * <p>A snapshot that re-vetting revoked (GW_0050) comes back through this exact method and no
     * other. That is what "not re-publishable without a fresh approve decision" means concretely:
     * the same gate, evaluated against the same effective outcome — so the violation that caused
     * the revocation must have been waived, or fixed by re-ingestion, before this can succeed — and
     * a new reviewer identity and timestamp recorded by the transition. There is no un-revoke.
     *
     * <p>Last of the preconditions — after the vetting gate and the policy gate — is the cooling-off
     * window (GW_0073): a snapshot whose commit the gateway first ingested less than the configured
     * minimum release age ago is refused, however clear its verdicts and rules are. It goes last
     * deliberately. All three refusals are disqualifying, but the other two tell a reviewer about
     * something to do — waive these findings, or take that rule up with whoever owns it — while
     * this one only says how long to wait, and reporting the actionable ones first is what lets
     * that work happen <em>during</em> the window rather than after it. Rejection is not gated at
     * all: refusing to let a reviewer say no to suspicious content quickly would invert the purpose
     * of the window.
     *
     * @return the decided snapshot together with the waivers that let it through
     */
    @Requirements({"GW_0005", "GW_0041", "GW_0050", "GW_0073"})
    public Approved approve(long snapshotId, String reviewer) {
        return approve(snapshotId, reviewer, ApprovalOverride.none());
    }

    /**
     * As {@link #approve(long, String)}, but with an administrator's override of a blocked vetting
     * outcome (GW_0148). When {@code override.vettingFailure()} is set and the effective outcome is
     * blocked, the vetting gate is lifted instead of refusing — the reason is required, the block
     * is recorded as a distinct ledger event and a standing marker on the snapshot, and every other
     * gate (policy, cooling-off, four-eyes) still runs. The admin-only nature of the override is
     * enforced by the caller; this method assumes an override request has already been authorized.
     */
    @Requirements({"GW_0005", "GW_0041", "GW_0050", "GW_0073", "GW_0148"})
    public Approved approve(long snapshotId, String reviewer, ApprovalOverride override) {
        // Observation only (GW_0077): timing and outcome around the unchanged decision — a
        // vetting-blocked refusal is the observation's error and still propagates untouched.
        return metrics.observeApproval("approve", () -> doApprove(snapshotId, reviewer, override));
    }

    private Approved doApprove(long snapshotId, String reviewer, ApprovalOverride overrideRequest) {
        // The state machine comes first: a snapshot that is neither held nor revoked is unapprovable
        // for a reason that has nothing to do with vetting, and saying "vetting blocked it" would
        // be wrong.
        Snapshot current =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        Marketplace marketplace = marketplaceRepository
                .findById(current.marketplaceId())
                .orElseThrow(
                        () -> new ApprovalException("marketplace %d not found".formatted(current.marketplaceId())));
        List<WaiverEvaluation.Suppression> applied = List.of();
        List<FourEyesConflictException.Conflict> conflicts = List.of();
        Duration ingestionAge = Duration.ZERO;
        OverrideCapture override = null;
        if (current.decidable()) {
            WaiverEvaluation.Effect effect = waiverService.evaluate(current);
            if (effect.blocked()) {
                // No blanket override existed here by design; GW_0148 adds one, and only for an
                // administrator who states a reason. When one is requested the vetting gate is
                // lifted — the block is recorded rather than refused — and every other gate below
                // still runs. Without a request the refusal is exactly as before.
                if (!overrideRequest.vettingFailure()) {
                    throw new VettingBlockedException(snapshotId, effect.blockingConnectors(), effect.uncovered());
                }
                if (!overrideRequest.hasReason()) {
                    throw new MissingOverrideReasonException(snapshotId);
                }
                override = OverrideCapture.of(overrideRequest.reason(), effect);
            }
            applied = effect.suppressions();
            // The policy gate (GW_0090) comes after vetting and before the state transition: every
            // enabled deny rule is evaluated over facts built at this instant, fail-closed — a rule
            // that matches, errors, or cannot see the facts refuses the approval, records the
            // decision on the ledger (GW_0091), and leaves the snapshot held with nothing published.
            policyGate.enforce(current, marketplace, reviewer);
            ingestionAge = requireReleaseAge(current, marketplace, reviewer);
            // Separation of duties last, and after waiver evaluation (GW_0096): the set of waivers
            // this approval leans on is only known once the effective outcome has been computed,
            // and one of them being the reviewer's own is a conflict. In enforce mode this throws
            // before the state transition below, so a refused approval leaves the snapshot held
            // with nothing published; in warn mode it returns what it found for the ledger.
            conflicts = requireFourEyes(current, marketplace, applied, reviewer);
        }
        Snapshot decided = snapshotRepository.decide(snapshotId, Snapshot.APPROVED, reviewer);
        String sha = decided.sha();
        try {
            // Publication is one seam operation (GW_0132): both served references land or neither
            // does, and a transition that did not take effect is raised rather than returned. What
            // used to be here -- a fetch built from the quarantine's filesystem path, then a
            // RefUpdate whose Result was discarded -- could not work on a backend whose
            // repositories have no path, and reported success when the served reference refused to
            // move.
            storage.publish(marketplace.name(), sha);
        } catch (IOException | RuntimeException publishFailed) {
            repair(current, publishFailed);
            throw new ApprovalException("publish failed for snapshot %d".formatted(snapshotId), publishFailed);
        }
        // The override marker is written only once the publication has actually landed (GW_0148):
        // the snapshot is now served over a vetting failure, so the standing marker that says so
        // must not exist for a snapshot that was never published. The ledger event is written by
        // the caller once approve returns, beside snapshot-approved, exactly as the four-eyes
        // warn-mode conflict is.
        VettingOverrideRecord overrideRecord = override == null
                ? null
                : vettingOverrideRepository.record(
                        snapshotId,
                        override.reason(),
                        override.blockingConnectors(),
                        override.uncoveredFindings(),
                        reviewer);
        // The published set just grew; the catalog re-derives from it (GW_0062). Never fails the
        // approval that triggered it.
        catalogService.rebuildQuietly();
        return new Approved(decided, applied, ingestionAge, conflicts, overrideRecord);
    }

    /**
     * What was blocking at the moment an administrator overrode the vetting gate (GW_0148),
     * rendered for the ledger, the standing marker, and the refusal-that-was-not. Captured from the
     * effective outcome before the state transition, so it names exactly what the administrator
     * took responsibility for.
     */
    private record OverrideCapture(String reason, String blockingConnectors, String uncoveredFindings) {

        static OverrideCapture of(String reason, WaiverEvaluation.Effect effect) {
            String connectors = String.join(", ", effect.blockingConnectors());
            String findings = effect.uncovered().isEmpty()
                    ? "(none itemised)"
                    : effect.uncovered().stream()
                            .map(finding -> "%s at %s".formatted(finding.ruleId(), finding.location()))
                            .collect(java.util.stream.Collectors.joining("; "));
            return new OverrideCapture(reason, connectors.isBlank() ? null : connectors, findings);
        }
    }

    /** The administrator's override of a blocked vetting outcome for this snapshot, or empty. */
    @Requirements({"GW_0148"})
    public Optional<VettingOverrideRecord> vettingOverride(long snapshotId) {
        return vettingOverrideRepository.findBySnapshot(snapshotId);
    }

    /**
     * Puts the row back after a publication that did not happen (GW_0133).
     *
     * <p>{@code decide} has already committed by this point, so the row says {@code approved} while
     * nothing is served. The caller writes the ledger entry and emits the event only once
     * {@code approve} returns, so failing before returning is what keeps those honest; the row is
     * the one thing that needs undoing.
     *
     * <p>A repair that itself fails is not swallowed. It is attached to the failure that caused it,
     * because at that point the estate is genuinely inconsistent — a row claiming a publication that
     * did not happen — and that is the case the startup check comparing the served estate against
     * the database exists to catch. Logged at error, since nothing downstream will see it again.
     */
    private void repair(Snapshot before, Throwable publishFailed) {
        try {
            if (snapshotRepository.undecide(before).isEmpty()) {
                log.warn(
                        "publication of snapshot {} failed and its row was no longer approved, so it was left"
                                + " alone: something else decided it in the meantime",
                        before.id());
            }
        } catch (RuntimeException repairFailed) {
            log.error(
                    "publication of snapshot {} failed and the row could not be returned to {}: the database"
                            + " records an approval that was never served",
                    before.id(),
                    before.state(),
                    repairFailed);
            publishFailed.addSuppressed(repairFailed);
        }
    }

    /**
     * The cooling-off window (GW_0073), with the refusal appended to the ledger before it is
     * raised. A blocked approval that left no trace would make the control unauditable: from the
     * ledger alone one could not tell a window that was never tested from one that turned an
     * attempt away, which is precisely the event worth seeing twice in a row.
     *
     * @return how long ago the commit was first ingested, for the ledger entry of the decision
     */
    private Duration requireReleaseAge(Snapshot snapshot, Marketplace marketplace, String reviewer) {
        try {
            return releaseAgeGate.require(snapshot);
        } catch (SnapshotTooYoungException tooYoung) {
            auditLogger.record(
                    reviewer,
                    marketplace.name(),
                    EVENT_REFUSED,
                    snapshot.sha(),
                    "minimum-release-age: age=%s, remaining=%s"
                            .formatted(
                                    ReleaseAgeGate.format(Duration.ofSeconds(
                                            tooYoung.eligibility().ageSeconds())),
                                    ReleaseAgeGate.format(Duration.ofSeconds(
                                            tooYoung.eligibility().remainingSeconds()))));
            throw tooYoung;
        }
    }

    /**
     * The four-eyes gate, with a refusal appended to the ledger before it is raised - for the same
     * reason the cooling-off refusal is: a control that turns approvals away invisibly cannot be
     * audited, and a refused self-approval is the one event an operator most needs to see.
     */
    @Requirements({"GW_0096", "GW_0097"})
    private List<FourEyesConflictException.Conflict> requireFourEyes(
            Snapshot snapshot, Marketplace marketplace, List<WaiverEvaluation.Suppression> applied, String reviewer) {
        try {
            return fourEyesGate.require(snapshot, marketplace, applied, reviewer);
        } catch (FourEyesConflictException refused) {
            auditLogger.record(
                    reviewer,
                    marketplace.name(),
                    FourEyesGate.EVENT_CONFLICT,
                    snapshot.sha(),
                    "mode=ENFORCE, refused, conflicts=" + FourEyesGate.describe(refused.conflicts()));
            throw refused;
        }
    }

    /**
     * What the four-eyes rule would say about this reviewer and this snapshot, without deciding
     * anything (GW_0096). Evaluates the waivers exactly as an approval would, so the answer names
     * the waiver conflicts a real approval would raise rather than an approximation of them.
     */
    @Requirements({"GW_0096"})
    public Optional<FourEyesGate.FourEyesCheck> fourEyes(long snapshotId, String reviewer) {
        return snapshotRepository.findById(snapshotId).map(snapshot -> {
            Marketplace marketplace =
                    marketplaceRepository.findById(snapshot.marketplaceId()).orElse(null);
            List<WaiverEvaluation.Suppression> applied =
                    snapshot.decidable() ? waiverService.evaluate(snapshot).suppressions() : List.of();
            List<FourEyesConflictException.Conflict> conflicts =
                    fourEyesGate.conflicts(snapshot, marketplace, applied, reviewer);
            return new FourEyesGate.FourEyesCheck(
                    fourEyesGate.mode(), conflicts, !conflicts.isEmpty() && fourEyesGate.enforcing());
        });
    }

    /** Whether the snapshot has cleared the cooling-off window, and when it will if it has not. */
    @Requirements({"GW_0073"})
    public Optional<ReleaseAgeGate.Eligibility> releaseAge(long snapshotId) {
        return snapshotRepository.findById(snapshotId).map(releaseAgeGate::evaluate);
    }

    public Snapshot reject(long snapshotId, String reviewer) {
        return metrics.observeApproval(
                "reject", () -> snapshotRepository.decide(snapshotId, Snapshot.REJECTED, reviewer));
    }

    @Requirements({"GW_0009"})
    public Optional<Provenance> provenance(long snapshotId) {
        return snapshotRepository.findById(snapshotId).map(snapshot -> {
            Marketplace marketplace =
                    marketplaceRepository.findById(snapshot.marketplaceId()).orElse(null);
            return new Provenance(
                    snapshot.id(),
                    marketplace == null ? null : marketplace.name(),
                    marketplace == null ? null : marketplace.url(),
                    marketplace == null ? null : marketplace.origin(),
                    snapshot.sha(),
                    snapshot.state(),
                    snapshot.violation(),
                    snapshot.createdAt(),
                    snapshot.decidedBy(),
                    snapshot.decidedAt(),
                    snapshot.revokedAt(),
                    snapshot.revokedBy());
        });
    }

    @Schema(description = "Provenance of a snapshot: what was served, from where, and who approved it")
    public record Provenance(
            long snapshotId,
            String marketplace,

            /** Null for a gateway-hosted marketplace, which has no upstream at all (GW_0101). */
            String upstreamUrl,

            /** {@code upstream} or {@code hosted}: tells "no upstream" from "not recorded". */
            String origin,

            String upstreamSha,
            String state,
            String violation,
            Instant ingestedAt,
            String decidedBy,
            Instant decidedAt,

            @Schema(description = "When re-vetting revoked the snapshot, or null")
            Instant revokedAt,

            @Schema(description = "Identity that revoked it, or null")
            String revokedBy) {}
}

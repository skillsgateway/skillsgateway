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
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.springframework.stereotype.Service;

@Service
public class ApprovalService {

    private final GitStorage storage;
    private final SnapshotRepository snapshotRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final WaiverService waiverService;
    private final PolicyGate policyGate;
    private final CatalogService catalogService;
    private final GatewayMetrics metrics;
    private final ReleaseAgeGate releaseAgeGate;
    private final AdminAuditLogger auditLogger;

    public ApprovalService(
            GitStorage storage,
            SnapshotRepository snapshotRepository,
            MarketplaceRepository marketplaceRepository,
            WaiverService waiverService,
            PolicyGate policyGate,
            CatalogService catalogService,
            GatewayMetrics metrics,
            ReleaseAgeGate releaseAgeGate,
            AdminAuditLogger auditLogger) {
        this.storage = storage;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.waiverService = waiverService;
        this.policyGate = policyGate;
        this.catalogService = catalogService;
        this.metrics = metrics;
        this.releaseAgeGate = releaseAgeGate;
        this.auditLogger = auditLogger;
    }

    /** Ledger event for an approval the cooling-off window refused (GW_0073). */
    static final String EVENT_REFUSED = "snapshot-approval-refused";

    /**
     * An approved snapshot, the waivers that were in force when the gate let it through — empty for
     * a snapshot the chain cleared on its own merits (GW_0048) — and how long ago the gateway first
     * ingested its commit, which the ledger records against the decision (GW_0073).
     */
    public record Approved(
            Snapshot snapshot, List<WaiverEvaluation.Suppression> waiversApplied, Duration ingestionAge) {}

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
        // Observation only (GW_0077): timing and outcome around the unchanged decision — a
        // vetting-blocked refusal is the observation's error and still propagates untouched.
        return metrics.observeApproval("approve", () -> doApprove(snapshotId, reviewer));
    }

    private Approved doApprove(long snapshotId, String reviewer) {
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
        Duration ingestionAge = Duration.ZERO;
        if (current.decidable()) {
            WaiverEvaluation.Effect effect = waiverService.evaluate(current);
            if (effect.blocked()) {
                throw new VettingBlockedException(snapshotId, effect.blockingConnectors(), effect.uncovered());
            }
            applied = effect.suppressions();
            // The policy gate (GW_0090) comes after vetting and before the state transition: every
            // enabled deny rule is evaluated over facts built at this instant, fail-closed — a rule
            // that matches, errors, or cannot see the facts refuses the approval, records the
            // decision on the ledger (GW_0091), and leaves the snapshot held with nothing published.
            policyGate.enforce(current, marketplace, reviewer);
            ingestionAge = requireReleaseAge(current, marketplace, reviewer);
        }
        Snapshot decided = snapshotRepository.decide(snapshotId, Snapshot.APPROVED, reviewer);
        String sha = decided.sha();
        try (Repository quarantine = storage.quarantine(marketplace.name());
                Repository published = storage.published(marketplace.name());
                Git git = new Git(published)) {
            git.fetch()
                    .setRemote(quarantine.getDirectory().getAbsolutePath())
                    .setRefSpecs(new RefSpec("+refs/snapshots/" + sha + ":refs/snapshots/" + sha))
                    .call();
            RefUpdate main = published.updateRef("refs/heads/main");
            main.setNewObjectId(ObjectId.fromString(sha));
            main.forceUpdate();
        } catch (IOException | GitAPIException e) {
            throw new ApprovalException("publish failed for snapshot %d".formatted(snapshotId), e);
        }
        // The published set just grew; the catalog re-derives from it (GW_0062). Never fails the
        // approval that triggered it.
        catalogService.rebuildQuietly();
        return new Approved(decided, applied, ingestionAge);
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

package io.github.jimisola.skillsgateway.approval;

import io.github.jimisola.skillsgateway.persistence.Marketplace;
import io.github.jimisola.skillsgateway.persistence.MarketplaceRepository;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.jimisola.skillsgateway.persistence.SnapshotNotFoundException;
import io.github.jimisola.skillsgateway.persistence.SnapshotRepository;
import io.github.jimisola.skillsgateway.storage.GitStorage;
import io.github.jimisola.skillsgateway.vetting.WaiverEvaluation;
import io.github.jimisola.skillsgateway.vetting.WaiverService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
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

    public ApprovalService(
            GitStorage storage,
            SnapshotRepository snapshotRepository,
            MarketplaceRepository marketplaceRepository,
            WaiverService waiverService) {
        this.storage = storage;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.waiverService = waiverService;
    }

    /**
     * An approved snapshot together with the waivers that were in force when the gate let it
     * through — empty for a snapshot the chain cleared on its own merits (GW_0048).
     */
    public record Approved(Snapshot snapshot, List<WaiverEvaluation.Suppression> waiversApplied) {}

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
     * @return the decided snapshot together with the waivers that let it through
     */
    @Requirements({"GW_0005", "GW_0041", "GW_0050"})
    public Approved approve(long snapshotId, String reviewer) {
        // The state machine comes first: a snapshot that is neither held nor revoked is unapprovable
        // for a reason that has nothing to do with vetting, and saying "vetting blocked it" would
        // be wrong.
        Snapshot current =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        List<WaiverEvaluation.Suppression> applied = List.of();
        if (current.decidable()) {
            WaiverEvaluation.Effect effect = waiverService.evaluate(current);
            if (effect.blocked()) {
                throw new VettingBlockedException(snapshotId, effect.blockingConnectors(), effect.uncovered());
            }
            applied = effect.suppressions();
        }
        Snapshot decided = snapshotRepository.decide(snapshotId, Snapshot.APPROVED, reviewer);
        Marketplace marketplace = marketplaceRepository
                .findById(decided.marketplaceId())
                .orElseThrow(
                        () -> new ApprovalException("marketplace %d not found".formatted(decided.marketplaceId())));
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
        return new Approved(decided, applied);
    }

    public Snapshot reject(long snapshotId, String reviewer) {
        return snapshotRepository.decide(snapshotId, Snapshot.REJECTED, reviewer);
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
            String upstreamUrl,
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

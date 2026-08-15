package io.github.jimisola.skillsgateway.approval;

import io.github.jimisola.skillsgateway.persistence.Marketplace;
import io.github.jimisola.skillsgateway.persistence.MarketplaceRepository;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.jimisola.skillsgateway.persistence.SnapshotNotFoundException;
import io.github.jimisola.skillsgateway.persistence.SnapshotRepository;
import io.github.jimisola.skillsgateway.storage.GitStorage;
import io.github.jimisola.skillsgateway.vetting.VettingRepository;
import io.github.jimisola.skillsgateway.vetting.VettingService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.time.Instant;
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
    private final VettingService vettingService;

    public ApprovalService(
            GitStorage storage,
            SnapshotRepository snapshotRepository,
            MarketplaceRepository marketplaceRepository,
            VettingService vettingService) {
        this.storage = storage;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.vettingService = vettingService;
    }

    /** Approval of a snapshot whose vetting chain did not object. */
    public Snapshot approve(long snapshotId, String reviewer) {
        return approve(snapshotId, reviewer, null);
    }

    /**
     * Records the decision, then copies the pinned commit to published and advances main.
     *
     * <p>The vetting gate comes first and fails closed (GW_0041): a snapshot whose latest chain run
     * blocked — including one that has no chain run at all — is refused unless the reviewer supplies
     * a reason, which is then recorded against the run and in the ledger by the caller. The check
     * precedes the state transition, so a refused approval leaves the snapshot held and publishes
     * nothing.
     *
     * <p>This blanket override is deliberately the minimum auditable escape hatch; scoped,
     * expiring, per-finding waivers replace it.
     */
    @Requirements({"GW_0005", "GW_0041"})
    public Snapshot approve(long snapshotId, String reviewer, String overrideReason) {
        // The state machine comes first: a snapshot that is not held is unapprovable for a reason
        // that has nothing to do with vetting, and saying "vetting blocked it" would be wrong.
        Snapshot current =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        boolean overridden = false;
        if (Snapshot.HELD.equals(current.state()) && vettingService.blocked(snapshotId)) {
            if (overrideReason == null || overrideReason.isBlank()) {
                throw new VettingBlockedException(
                        snapshotId,
                        vettingService
                                .latestRun(snapshotId)
                                .map(VettingRepository.Run::blockingConnectors)
                                .orElse(java.util.List.of()));
            }
            overridden = true;
        }
        Snapshot decided = snapshotRepository.decide(snapshotId, Snapshot.APPROVED, reviewer);
        if (overridden) {
            vettingService
                    .latestRun(snapshotId)
                    .ifPresent(run -> vettingService.recordOverride(run.runId(), reviewer, overrideReason));
        }
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
        return decided;
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
                    snapshot.decidedAt());
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
            Instant decidedAt) {}
}

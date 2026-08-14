package io.github.jimisola.skillsgateway.approval;

import io.github.jimisola.skillsgateway.persistence.Marketplace;
import io.github.jimisola.skillsgateway.persistence.MarketplaceRepository;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.jimisola.skillsgateway.persistence.SnapshotRepository;
import io.github.jimisola.skillsgateway.storage.GitStorage;
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

    public ApprovalService(
            GitStorage storage, SnapshotRepository snapshotRepository, MarketplaceRepository marketplaceRepository) {
        this.storage = storage;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
    }

    /** Records the decision, then copies the pinned commit to published and advances main. */
    @Requirements({"GW_0005"})
    public Snapshot approve(long snapshotId, String reviewer) {
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

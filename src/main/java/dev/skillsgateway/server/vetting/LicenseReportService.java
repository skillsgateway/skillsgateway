package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.ingestion.IngestionException;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The per-snapshot license report (GW_0095): a fresh deterministic detection over the content
 * pinned to the snapshot's commit SHA, evaluated under the license policy currently configured.
 *
 * <p>Deliberately recomputed rather than read back from a recorded chain run: detection is cheap,
 * the answer exists for snapshots that predate the license connector, and the report states current
 * policy truth — recorded runs remain the historical evidence the approval gate reads.
 */
@Service
public class LicenseReportService {

    private final GitStorage storage;
    private final SnapshotRepository snapshotRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final SkillsGatewayProperties properties;

    public LicenseReportService(
            GitStorage storage,
            SnapshotRepository snapshotRepository,
            MarketplaceRepository marketplaceRepository,
            SkillsGatewayProperties properties) {
        this.storage = storage;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.properties = properties;
    }

    @Schema(description = "One detected license and its standing under the configured policy")
    public record LicenseView(
            @Schema(description = "SPDX id, or null for the unknown-license state", example = "Apache-2.0")
            String spdxId,

            @Schema(
                    description = "Where it was detected",
                    allowableValues = {"file", "manifest"})
            String source,

            @Schema(description = "File path, or <manifest path>#<field> for manifest metadata")
            String location,

            @Schema(description = "Raw declared value for manifest metadata; null for files")
            String declared,

            @Schema(description = "Standing under the configured allow/ban policy")
            LicenseEvaluation evaluation) {}

    @Schema(description = "The licenses a snapshot declares, evaluated under the configured policy")
    public record LicenseReport(
            @Schema(description = "Snapshot id") long snapshotId,

            @Schema(description = "Upstream commit SHA the detection ran over")
            String sha,

            @Schema(description = "Every detection; empty means the snapshot carries no license information")
            List<LicenseView> licenses,

            @Schema(description = "The configured allow list (SPDX ids); empty means not configured")
            List<String> allowed,

            @Schema(description = "The configured ban list (SPDX ids)")
            List<String> banned) {}

    @Requirements({"GW_0095"})
    public LicenseReport report(long snapshotId) {
        Snapshot snapshot =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        Marketplace marketplace = marketplaceRepository
                .findById(snapshot.marketplaceId())
                .orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        SkillsGatewayProperties.License config = properties.vetting().license();
        LicensePolicy policy = new LicensePolicy(config);
        try (QuarantineSnapshot content = new QuarantineSnapshot(
                snapshot.id(),
                marketplace.name(),
                snapshot.sha(),
                properties.vetting().maxFileBytes(),
                properties.vetting().contentCacheBytes(),
                storage.quarantine(marketplace.name()))) {
            List<LicenseView> licenses = LicenseDetector.detect(content).stream()
                    .map(detection -> new LicenseView(
                            detection.spdxId(),
                            detection.source() == LicenseDetector.Source.FILE ? "file" : "manifest",
                            detection.location(),
                            detection.declared(),
                            policy.evaluate(detection)))
                    .toList();
            return new LicenseReport(snapshot.id(), snapshot.sha(), licenses, config.allowed(), config.banned());
        } catch (IOException e) {
            throw new IngestionException(
                    "cannot read licenses of snapshot %d (%s)".formatted(snapshotId, snapshot.sha()), e);
        }
    }
}

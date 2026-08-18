package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.swagger.v3.oas.annotations.media.Schema;
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

    /** How a detection stands under the configured policy. */
    @Schema(description = "How a detected license stands under the configured allow/ban policy")
    public enum Evaluation {

        /** Identified, not banned, and permitted by the allow list (or no allow list configured). */
        OK,

        /** Identified and on the configured ban list. */
        BANNED,

        /** Identified, but a non-empty allow list is configured and does not contain it. */
        NOT_ALLOWED,

        /** The source identifies no known license; blocking when an allow list is configured. */
        UNKNOWN
    }

    @Schema(description = "One detected license and its standing under the configured policy")
    public record LicenseView(
            @Schema(description = "SPDX id, or null for the unknown-license state", example = "Apache-2.0")
            String spdxId,

            @Schema(description = "Where it was detected", allowableValues = {"file", "manifest"})
            String source,

            @Schema(description = "File path, or <manifest path>#<field> for manifest metadata")
            String location,

            @Schema(description = "Raw declared value for manifest metadata; null for files")
            String declared,

            @Schema(description = "Standing under the configured allow/ban policy")
            Evaluation evaluation) {}

    @Schema(description = "The licenses a snapshot declares, evaluated under the configured policy")
    public record LicenseReport(
            @Schema(description = "Snapshot id") long snapshotId,
            @Schema(description = "Upstream commit SHA the detection ran over") String sha,

            @Schema(description = "Every detection; empty means the snapshot carries no license information")
            List<LicenseView> licenses,

            @Schema(description = "The configured allow list (SPDX ids); empty means not configured")
            List<String> allowed,

            @Schema(description = "The configured ban list (SPDX ids)")
            List<String> banned) {}

    public LicenseReport report(long snapshotId) {
        throw new UnsupportedOperationException("not implemented");
    }
}

package dev.skillsgateway.server.persistence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "A registered marketplace, fetched from an upstream or hosted by the gateway")
public record Marketplace(
        long id,
        String name,

        @Schema(description = "Upstream clone URL; null for a gateway-hosted marketplace")
        String url,

        Instant createdAt,

        @Schema(
                description = "Identity that registered the marketplace, or null for one registered before the"
                        + " registrant was recorded")
        String registeredBy,

        @Schema(
                description = "Where the content comes from: fetched from an upstream clone URL, or pushed"
                        + " by the organisation into a gateway-owned origin repository",
                allowableValues = {"upstream", "hosted"})
        String origin,

        @Schema(
                description = "Whether a hosted marketplace's publisher may rewrite its lineage;"
                        + " meaningless for an upstream marketplace",
                allowableValues = {"append-only", "allow-rewrite"})
        String pushPolicy,

        @Schema(description = "Detected forge (github, gitlab, bitbucket, azure-devops, gitea) or null")
        String forge,

        @Schema(description = "Project path on the forge") String forgeProject,

        @Schema(description = "Project description from the forge")
        String description,

        @Schema(description = "Last upstream update as reported by the forge")
        Instant upstreamUpdatedAt,

        @Schema(
                description = "How upstream content reaches quarantine: on-demand, scheduled, or webhook."
                        + " The trigger only — every mode lands snapshots held behind the approval gate.",
                allowableValues = {"on-demand", "scheduled", "webhook"})
        String syncMode,

        @Schema(description = "Last sync attempt (success or failure), or null before the first one")
        Instant lastSyncAt) {

    /**
     * The one history a marketplace has, on both sides of the gateway: what a hosted publisher
     * may push and what the facade serves. Its single-lineage guarantee (GW_0017) is what makes a
     * snapshot's provenance a straight line rather than a choice of branches.
     */
    public static final String LINEAGE_REF = "refs/heads/main";

    /** Fetched from an upstream clone URL — the original and default kind. */
    public static final String ORIGIN_UPSTREAM = "upstream";

    /** Pushed by the organisation into a gateway-owned origin repository (GW_0101). */
    public static final String ORIGIN_HOSTED = "hosted";

    /** A push must fast-forward the lineage; the default. */
    public static final String PUSH_APPEND_ONLY = "append-only";

    /** A push may rewrite the lineage, and both tips land on the ledger when it does. */
    public static final String PUSH_ALLOW_REWRITE = "allow-rewrite";

    public boolean hosted() {
        return ORIGIN_HOSTED.equals(origin);
    }

    public static final String SYNC_ON_DEMAND = "on-demand";
    public static final String SYNC_SCHEDULED = "scheduled";
    public static final String SYNC_WEBHOOK = "webhook";
}

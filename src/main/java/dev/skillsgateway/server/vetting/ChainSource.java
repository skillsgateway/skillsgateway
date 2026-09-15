package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where a vetter's effective enablement for one marketplace came from (GW_VETTING_0029.1). The absence
 * of any setting is its own source rather than a missing value: an administrator reading a chain
 * has to be able to tell a default apart from a decision somebody made.
 */
@Schema(description = "Which setting decided a vetter's effective state for a marketplace")
public enum ChainSource {

    /** A setting scoped to this marketplace, which overrides the global one. */
    MARKETPLACE,

    /** The global setting, there being none for this marketplace. */
    GLOBAL,

    /** No setting at all, so the vetter runs. */
    DEFAULT
}

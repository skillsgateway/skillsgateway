package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;

/** How one detected license stands under the configured allow/ban policy (GW_0090). */
@Schema(description = "How a detected license stands under the configured allow/ban policy")
public enum LicenseEvaluation {

    /** Identified, not banned, and permitted by the allow list (or no allow list configured). */
    OK,

    /** Identified and on the configured ban list. */
    BANNED,

    /** Identified, but a non-empty allow list is configured and does not contain it. */
    NOT_ALLOWED,

    /** The source identifies no known license; blocking once an allow list is configured. */
    UNKNOWN
}

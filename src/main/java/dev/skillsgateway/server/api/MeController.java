package dev.skillsgateway.server.api;

import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session identity for the portal: the BFF session is the only credential the browser holds. */
@RestController
public class MeController {

    private final RoleService roleService;

    /**
     * The build's own version, or null (GW_AUTH_0047).
     *
     * <p>Read once at construction from Spring Boot's {@code BuildProperties}, which exists because
     * this build runs the {@code build-info} goal. An {@link ObjectProvider} because it genuinely
     * may be absent — every test context runs from classes rather than from a packaged jar — and
     * absent must stay absent: a placeholder string would be the gateway answering "which build
     * are you?" with something that is not true.
     */
    private final String version;

    public MeController(RoleService roleService, ObjectProvider<BuildProperties> buildProperties) {
        this.roleService = roleService;
        this.version = buildProperties.getIfAvailable(() -> null) == null
                ? null
                : buildProperties.getObject().getVersion();
    }

    @Schema(description = "The authenticated browser session's identity and effective roles")
    public record MeView(
            @Schema(description = "Username of the session") String username,

            @Schema(description = "The session's effective roles, config-bootstrapped and claim-derived included")
            List<RoleService.EffectiveRole> roles,

            @Schema(
                    description = "Whether the identity provider dropped the membership claim rather than the"
                            + " session having none — the roles above are then incomplete")
            boolean claimsTruncated,

            @Schema(
                    description = "Version of the running gateway build (GW_AUTH_0047), or null when the artifact"
                            + " carries no build information. Taken from the build itself and from no"
                            + " configurable source, so it cannot be set to something the gateway is not.",
                    example = "0.3.0")
            String version) {}

    @GetMapping("/api/v1/me")
    @Requirements({"GW_AUTH_0013", "GW_AUTH_0015.3", "GW_AUTH_0016", "GW_AUTH_0047"})
    @Tag(name = "Session")
    @Operation(
            summary = "Current user",
            description = "Username of the authenticated browser session, the session's effective roles with"
                    + " the source of each, and whether the identity provider truncated the membership"
                    + " claim — how the portal and CLI adapt their controls to what the caller may do."
                    + " Authorization is always enforced, so there is no enforcement flag to report."
                    + " Also reports the running build's version, which the portal shell states: the"
                    + " portal already makes this call once per load, so the gateway's own"
                    + " self-description costs no second request.")
    public MeView me(Authentication authentication) {
        return new MeView(
                authentication.getName(),
                roleService.effectiveRoles(authentication),
                roleService.claimsTruncated(authentication),
                version);
    }
}

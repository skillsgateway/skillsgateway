package io.github.jimisola.skillsgateway.api;

import io.github.jimisola.skillsgateway.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session identity for the portal: the BFF session is the only credential the browser holds. */
@RestController
public class MeController {

    private final RoleService roleService;

    public MeController(RoleService roleService) {
        this.roleService = roleService;
    }

    @Schema(description = "The authenticated browser session's identity and effective roles")
    public record MeView(
            @Schema(description = "Username of the session") String username,

            @Schema(description = "Whether role enforcement is enabled; false means every check passes")
            boolean rolesEnabled,

            @Schema(description = "The session's effective roles, config-bootstrapped admin included")
            List<RoleService.EffectiveRole> roles) {}

    @GetMapping("/api/me")
    @Requirements({"GW_0071"})
    @Tag(name = "Session")
    @Operation(
            summary = "Current user",
            description = "Username of the authenticated browser session, whether role enforcement is"
                    + " enabled, and the session's effective roles — how the portal and CLI adapt their"
                    + " controls to what the caller may do.")
    public MeView me(Authentication authentication) {
        return new MeView(
                authentication.getName(), roleService.enabled(), roleService.rolesOf(authentication.getName()));
    }
}

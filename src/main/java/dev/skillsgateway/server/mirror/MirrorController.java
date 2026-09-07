package dev.skillsgateway.server.mirror;

import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MirrorController {

    private final ForgeMirrorService mirror;
    private final RoleService roleService;

    public MirrorController(ForgeMirrorService mirror, RoleService roleService) {
        this.mirror = mirror;
        this.roleService = roleService;
    }

    /**
     * Administrator-only, not an auditor read: it names an outbound integration target and the
     * state of the credential's last use, which is deployment infrastructure rather than a record
     * of what the gateway served to whom. It is on the machine API's unreachable list for the same
     * reason.
     */
    @GetMapping("/mirror/drift")
    @Tag(name = "Mirror")
    @Operation(
            summary = "Compare the read-only forge mirror against what the facade serves",
            description = "Reads the mirror's references now and diffs them against published storage."
                    + " The mirror is a browsing convenience and never a serving surface, so a mirror that"
                    + " is missing, stale or unreachable says nothing about what clients receive. A mirror"
                    + " the gateway cannot read is reported as unreachable and never as in sync.")
    @ApiResponse(responseCode = "200", description = "The comparison, or a disabled report when no mirror is set up")
    @ApiResponse(responseCode = "403", description = "Not an administrator")
    @Requirements({"GW_0172"})
    public MirrorReport drift(Authentication authentication) {
        roleService.requireAdmin(authentication);
        return mirror.report();
    }
}

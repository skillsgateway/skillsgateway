package dev.skillsgateway.server.auth;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An administrator's reach over another principal's personal tokens (GW_AUTH_0055): the listing and
 * revocation that offboarding and incident response need, which the owner-scoped routes cannot give
 * anyone but the owner. Under {@code /api/tokens/**}, which no machine credential reaches.
 */
@RestController
@RequestMapping("/api/v1/tokens/principals/{principal}")
public class PrincipalTokenController {

    /** Credential events are not tied to a marketplace; the ledger column is NOT NULL. */
    private static final String NO_MARKETPLACE = "-";

    private final TokenService tokenService;
    private final RoleService roleService;
    private final AdminAuditLogger auditLogger;

    public PrincipalTokenController(TokenService tokenService, RoleService roleService, AdminAuditLogger auditLogger) {
        this.tokenService = tokenService;
        this.roleService = roleService;
        this.auditLogger = auditLogger;
    }

    @Schema(description = "The personal tokens an administrative revocation revoked")
    public record RevokedTokens(
            @Schema(description = "The principal whose tokens were revoked")
            String principal,

            @Schema(description = "Ids of the tokens that were live and are now revoked; empty when none were")
            List<Long> revokedTokenIds) {}

    @GetMapping
    @Requirements({"GW_AUTH_0055"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "List a principal's personal tokens",
            description = "Every personal access token and session credential the principal holds, live or"
                    + " not, never any secret. Machine credentials are listed under `/api/v1/tokens/machine`.")
    @ApiResponse(responseCode = "200", description = "The principal's personal tokens; empty for an unknown principal")
    @ApiResponse(responseCode = "403", description = "The caller does not hold the admin role")
    public List<TokenController.TokenView> list(@PathVariable String principal, Authentication authentication) {
        roleService.requireAdmin(authentication);
        return tokenService.listPersonalTokensOf(principal).stream()
                .map(TokenController::view)
                .toList();
    }

    @DeleteMapping
    @Requirements({"GW_AUTH_0055"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "Revoke all of a principal's personal tokens",
            description = "Revokes every live personal access token and session credential the principal"
                    + " holds, effective on each one's next request. Machine credentials are left alone. The"
                    + " revocation is audit-logged with the ids it revoked, including when there were none.")
    @ApiResponse(responseCode = "200", description = "Revoked; the ids revoked, empty when none were live")
    @ApiResponse(responseCode = "403", description = "The caller does not hold the admin role")
    public RevokedTokens revokeAll(@PathVariable String principal, Authentication authentication) {
        roleService.requireAdmin(authentication);
        List<Long> revoked = tokenService.revokePersonalTokensOf(principal);
        auditLogger.record(
                authentication.getName(),
                NO_MARKETPLACE,
                "principal-tokens-revoked",
                null,
                "principal=%s tokens=%s".formatted(principal, revoked));
        return new RevokedTokens(principal, revoked);
    }
}

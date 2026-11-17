package dev.skillsgateway.server.auth;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.persistence.AccessToken;
import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provisioning and administration of machine API credentials (GW_AUTH_0020, GW_AUTH_0024).
 *
 * <p><b>Deliberately under {@code /api/tokens/**}</b>, which the machine-reachability registry
 * classifies wholly unreachable. That placement is a security constraint rather than a routing
 * convenience: a credential able to mint a sibling is self-replicating privilege, and it can evade
 * its own revocation by minting a replacement before an administrator gets there. The first
 * machine credential is therefore always minted by a person from a browser session — which is
 * worth saying plainly, because it is the same scripted-session workaround this capability exists
 * to remove. The difference is that it happens once, to create something with a stated expiry and
 * named scopes, rather than continuously to run a pipeline.
 *
 * <p>Every path here requires the {@code admin} role; see {@link RoleService#requireAdmin}.
 */
@RestController
@RequestMapping("/api/v1/tokens/machine")
public class MachineTokenController {

    /** Credential events are not tied to a marketplace; the ledger column is NOT NULL. */
    private static final String NO_MARKETPLACE = "-";

    private final TokenService tokenService;
    private final RoleService roleService;
    private final AdminAuditLogger auditLogger;

    public MachineTokenController(TokenService tokenService, RoleService roleService, AdminAuditLogger auditLogger) {
        this.tokenService = tokenService;
        this.roleService = roleService;
        this.auditLogger = auditLogger;
    }

    @Schema(description = "Machine API credential request; every field below is required")
    public record CreateMachineCredentialRequest(
            @Schema(
                    description = "The principal the credential acts as. It is not a person: the ledger"
                            + " attributes this credential's actions to it, and role grants name it.",
                    example = "terraform-ci",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String principal,

            @Schema(
                    description = "Human-readable credential name",
                    example = "platform-pipeline",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            @Schema(
                    description = "Named API scopes. There is no wildcard, no scope implies another, and an"
                            + " unknown value is refused rather than silently never matching.",
                    example = "[\"marketplaces:register\", \"estate:read\"]",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> apiScopes,

            @Schema(
                    description = "Expiry. Either this or neverExpires: true is mandatory and neither is ever"
                            + " defaulted; a lifetime beyond the cap (one year unless the deployment configures"
                            + " one) is refused rather than shortened.")
            Instant expiresAt,

            @Schema(
                    description = "States, explicitly, that the credential never expires. Refused together with"
                            + " an expiry, and refused whenever the deployment configures a token lifetime"
                            + " cap. Omitting it is the same as false: an omitted expiry is refused.")
            Boolean neverExpires) {}

    /** Never exposes the stored hash; the cleartext is returned only at creation and rotation. */
    @Schema(description = "A machine API credential without its secret")
    public record MachineCredentialView(
            @Schema(description = "Credential id") long id,

            @Schema(description = "The principal the credential acts as")
            String principal,

            @Schema(description = "Credential name") String name,
            @Schema(description = "Creation time") Instant createdAt,

            @Schema(description = "Revocation time, or null while active")
            Instant revokedAt,

            @Schema(description = "Expiry; never null for a machine credential")
            Instant expiresAt,

            @Schema(description = "The credential this one replaced by rotation, or null")
            Long rotatedFrom,

            @Schema(description = "The named API scopes it holds")
            List<String> apiScopes,

            @Schema(description = "The identity that provisioned it")
            String machineOwner,

            @Schema(
                    description = "When the credential most recently authenticated successfully, or null if"
                            + " it never has. Recorded at most once a minute, so it is approximate to that"
                            + " bound; the audit ledger is the exact, per-request record.")
            Instant lastUsedAt) {}

    @PostMapping
    @Requirements({"GW_AUTH_0020", "GW_AUTH_0023", "GW_AUTH_0024"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "Provision a machine API credential",
            description =
                    "Issues a non-interactive credential for the REST API, presented as"
                            + " `Authorization: Bearer`. It carries no fetch and no publication authority, reaches"
                            + " only the endpoints its named scopes allow, and can never reach an act of human"
                            + " judgement, a retraction of content, a role grant or this endpoint. An expiry, or an"
                            + " explicit never-expires, is mandatory. The cleartext is returned exactly once. Requires the admin role.")
    @ApiResponse(responseCode = "201", description = "Issued; the only response carrying the cleartext")
    @ApiResponse(responseCode = "403", description = "The caller does not hold the admin role")
    @ApiResponse(
            responseCode = "422",
            description = "Unknown or empty API scope, missing expiry, or a lifetime beyond the cap")
    public ResponseEntity<TokenService.IssuedToken> create(
            @RequestBody CreateMachineCredentialRequest request, Authentication authentication) {
        roleService.requireAdmin(authentication);
        TokenService.IssuedToken issued = tokenService.createMachineCredential(
                request.principal(),
                request.name(),
                request.apiScopes() == null ? List.of() : request.apiScopes(),
                request.expiresAt(),
                Boolean.TRUE.equals(request.neverExpires()),
                authentication.getName());
        // The provisioning identity is the actor on this entry, and is recorded as the
        // credential's owner: there is always a responsible person, named once here rather than
        // impersonated on every use of the credential.
        audit(authentication, "machine-credential-created", issued);
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }

    @GetMapping
    @Requirements({"GW_AUTH_0024", "GW_AUTH_0031"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "List machine API credentials",
            description = "Every machine credential, whoever provisioned it, never any secret. Deliberately"
                    + " not scoped to the caller: a machine credential's principal is not an identity anyone"
                    + " logs in as, so an owner-scoped listing would leave every one of them invisible —"
                    + " and unrevokable — during an incident. A person's own token listing is unaffected"
                    + " and still shows only their own. Each carries `lastUsedAt`: when the credential most"
                    + " recently authenticated successfully, or null if it never has.")
    @ApiResponse(responseCode = "200", description = "Every machine credential")
    @ApiResponse(responseCode = "403", description = "The caller does not hold the admin role")
    public List<MachineCredentialView> list(Authentication authentication) {
        roleService.requireAdmin(authentication);
        return tokenService.listMachineCredentials().stream()
                .map(MachineTokenController::view)
                .toList();
    }

    /** One API scope and the routes holding it reaches. */
    @Schema(description = "An API scope a machine credential may hold, and what it reaches")
    public record ApiScopeView(
            @Schema(description = "The scope name, as it is given in apiScopes", example = "estate:read")
            String scope,

            @Schema(description = "The routes this scope reaches, as \"METHOD /path\"")
            List<String> routes) {}

    @GetMapping("/scopes")
    @Requirements({"GW_AUTH_0076"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "List the API scopes a machine credential may hold",
            description = "Exactly the scopes the machine-reachability registry defines, sorted, each with the"
                    + " routes it reaches, so a client offering a choice never carries a copy that can drift."
                    + " There is no wildcard. Read-only, administrator-only, and like every route under"
                    + " /api/v1/tokens unreachable by a machine credential.")
    @ApiResponse(responseCode = "200", description = "Every scope with its routes")
    @ApiResponse(responseCode = "403", description = "The caller does not hold the admin role")
    public List<ApiScopeView> scopes(Authentication authentication) {
        roleService.requireAdmin(authentication);
        return MachineApiRegistry.scopes().stream()
                .sorted()
                .map(scope -> new ApiScopeView(
                        scope,
                        MachineApiRegistry.routesOf(scope).stream()
                                .sorted()
                                .map(Object::toString)
                                .toList()))
                .toList();
    }

    /** Nothing but the list (GW_AUTH_0087); see {@link TokenController.UpdateTokenScopesRequest} for why a class. */
    @Schema(description = "API scope change request: the scopes the credential holds, and nothing else")
    public static final class UpdateApiScopesRequest {
        private List<String> apiScopes;

        @Schema(
                description = "The complete list of API scopes the credential holds from now on. Required, at"
                        + " least one, each known to the registry; there is no wildcard.",
                example = "[\"estate:read\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        public List<String> getApiScopes() {
            return apiScopes;
        }

        public void setApiScopes(List<String> apiScopes) {
            this.apiScopes = apiScopes;
        }

        @JsonAnySetter
        void refuse(String field, Object ignored) {
            throw new TokenService.UnacceptedFieldException(field, "`apiScopes`");
        }
    }

    @PatchMapping("/{id}")
    @Requirements({"GW_AUTH_0084", "GW_AUTH_0085", "GW_AUTH_0086", "GW_AUTH_0087"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "Change the API scopes of a machine API credential",
            description = "Replaces the API scopes of a live credential, under every rule of provisioning: at"
                    + " least one scope, each one the registry defines, no wildcard. Only the API scopes change:"
                    + " the secret, expiry, name, principal and fetch scope are untouched, so whoever holds the"
                    + " existing secret has the new scopes on its next request. Requires the admin role, is"
                    + " unreachable by any credential, and is audit-logged with the scopes before and after. The"
                    + " body carries `apiScopes` and nothing else; any other field is refused.")
    @ApiResponse(responseCode = "200", description = "Changed; the credential as it now stands, never a secret")
    @ApiResponse(responseCode = "400", description = "The body carries a field other than `apiScopes`")
    @ApiResponse(responseCode = "403", description = "The caller does not hold the admin role")
    @ApiResponse(responseCode = "404", description = "No such machine credential")
    @ApiResponse(responseCode = "409", description = "The credential is revoked or expired")
    @ApiResponse(responseCode = "422", description = "Unknown, wildcard or empty API scopes, or `apiScopes` missing")
    public ResponseEntity<MachineCredentialView> updateApiScopes(
            @PathVariable long id, @RequestBody UpdateApiScopesRequest request, Authentication authentication) {
        roleService.requireAdmin(authentication);
        if (request == null || request.getApiScopes() == null) {
            throw new TokenService.InvalidTokenRequestException(
                    "apiScopes is required: a machine credential must name at least one API scope");
        }
        return tokenService
                .updateApiScopes(id, request.getApiScopes(), authentication.getName())
                .map(token -> ResponseEntity.ok(view(token)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/rotate")
    @Requirements({"GW_AUTH_0008", "GW_AUTH_0024"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "Rotate a machine API credential",
            description = "Issues a fresh secret with the identical grant — the same principal, name, expiry"
                    + " deadline and every one of the same API scopes — and revokes the old credential first,"
                    + " so no moment has two live secrets.")
    @ApiResponse(responseCode = "200", description = "Rotated; the only response carrying the new cleartext")
    @ApiResponse(responseCode = "403", description = "The caller does not hold the admin role")
    @ApiResponse(responseCode = "404", description = "No such machine credential")
    @ApiResponse(responseCode = "409", description = "The credential is revoked or expired; provision a new one")
    public ResponseEntity<TokenService.IssuedToken> rotate(@PathVariable long id, Authentication authentication) {
        roleService.requireAdmin(authentication);
        return tokenService
                .rotateMachineCredential(id)
                .map(issued -> {
                    audit(authentication, "machine-credential-rotated", issued);
                    return ResponseEntity.ok(issued);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Requirements({"GW_AUTH_0024"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "Revoke a machine API credential",
            description = "Immediate and permanent, checked at authentication time rather than swept, so it"
                    + " takes effect on the credential's very next request. Audit-logged.")
    @ApiResponse(responseCode = "204", description = "Revoked")
    @ApiResponse(responseCode = "403", description = "The caller does not hold the admin role")
    @ApiResponse(responseCode = "404", description = "No such active machine credential")
    public ResponseEntity<Void> revoke(@PathVariable long id, Authentication authentication) {
        roleService.requireAdmin(authentication);
        AccessToken credential = tokenService.findMachineCredential(id).orElse(null);
        if (!tokenService.revokeMachineCredential(id)) {
            return ResponseEntity.notFound().build();
        }
        auditLogger.record(
                authentication.getName(),
                NO_MARKETPLACE,
                "machine-credential-revoked",
                null,
                "credential %d '%s' principal=%s".formatted(id, credential.name(), credential.principal()));
        return ResponseEntity.noContent().build();
    }

    private void audit(Authentication authentication, String event, TokenService.IssuedToken issued) {
        auditLogger.record(
                authentication.getName(),
                NO_MARKETPLACE,
                event,
                null,
                "credential %d '%s' scopes=%s expires=%s"
                        .formatted(issued.id(), issued.name(), issued.apiScopes(), issued.expiresAt()));
    }

    /** A refused scope or lifetime is 422, the same shape the personal-token surface uses. */
    @ExceptionHandler(TokenService.InvalidTokenRequestException.class)
    public ProblemDetail invalidRequest(TokenService.InvalidTokenRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    /** A body naming a field the route does not own says so (GW_AUTH_0087); any other unreadable body is a plain 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return TokenService.unreadableBody(e);
    }

    @ExceptionHandler(TokenService.TokenNotLiveException.class)
    public ProblemDetail notLive(TokenService.TokenNotLiveException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(TokenService.TokenNotRotatableException.class)
    public ProblemDetail notRotatable(TokenService.TokenNotRotatableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    private static MachineCredentialView view(AccessToken token) {
        return new MachineCredentialView(
                token.id(),
                token.principal(),
                token.name(),
                token.createdAt(),
                token.revokedAt(),
                token.expiresAt(),
                token.rotatedFrom(),
                token.apiScopeList(),
                token.machineOwner(),
                token.lastUsedAt());
    }
}

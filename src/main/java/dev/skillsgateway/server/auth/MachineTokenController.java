package dev.skillsgateway.server.auth;

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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provisioning and administration of machine API credentials (GW_0126, GW_0131).
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
 * <p>Every path here requires the {@code admin} role <b>whether or not role enforcement is
 * enabled</b>; see {@link RoleService#requireAdminRegardlessOfEnforcement}.
 */
@RestController
@RequestMapping("/api/tokens/machine")
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
                    description = "Expiry. Mandatory and never defaulted; a lifetime beyond the cap is"
                            + " refused rather than shortened.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Instant expiresAt) {}

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
            String machineOwner) {}

    @PostMapping
    @Requirements({"GW_0126", "GW_0130", "GW_0131"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "Provision a machine API credential",
            description = "Issues a non-interactive credential for the REST API, presented as"
                    + " `Authorization: Bearer`. It carries no fetch and no publication authority, reaches"
                    + " only the endpoints its named scopes allow, and can never reach an act of human"
                    + " judgement, a retraction of content, a role grant or this endpoint. An expiry is"
                    + " mandatory. The cleartext is returned exactly once. Requires the admin role whether"
                    + " or not role enforcement is enabled.")
    @ApiResponse(responseCode = "201", description = "Issued; the only response carrying the cleartext")
    @ApiResponse(responseCode = "403", description = "The caller does not hold the admin role")
    @ApiResponse(
            responseCode = "422",
            description = "Unknown or empty API scope, missing expiry, or a lifetime beyond the cap")
    public ResponseEntity<TokenService.IssuedToken> create(
            @RequestBody CreateMachineCredentialRequest request, Authentication authentication) {
        roleService.requireAdminRegardlessOfEnforcement(authentication);
        TokenService.IssuedToken issued = tokenService.createMachineCredential(
                request.principal(),
                request.name(),
                request.apiScopes() == null ? List.of() : request.apiScopes(),
                request.expiresAt(),
                authentication.getName());
        // The provisioning identity is the actor on this entry, and is recorded as the
        // credential's owner: there is always a responsible person, named once here rather than
        // impersonated on every use of the credential.
        audit(authentication, "machine-credential-created", issued);
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }

    @GetMapping
    @Requirements({"GW_0131"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "List machine API credentials",
            description = "Every machine credential, whoever provisioned it, never any secret. Deliberately"
                    + " not scoped to the caller: a machine credential's principal is not an identity anyone"
                    + " logs in as, so an owner-scoped listing would leave every one of them invisible —"
                    + " and unrevokable — during an incident. A person's own token listing is unaffected"
                    + " and still shows only their own.")
    @ApiResponse(responseCode = "200", description = "Every machine credential")
    @ApiResponse(responseCode = "403", description = "The caller does not hold the admin role")
    public List<MachineCredentialView> list(Authentication authentication) {
        roleService.requireAdminRegardlessOfEnforcement(authentication);
        return tokenService.listMachineCredentials().stream()
                .map(MachineTokenController::view)
                .toList();
    }

    @PostMapping("/{id}/rotate")
    @Requirements({"GW_0066", "GW_0131"})
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
        roleService.requireAdminRegardlessOfEnforcement(authentication);
        return tokenService
                .rotateMachineCredential(id)
                .map(issued -> {
                    audit(authentication, "machine-credential-rotated", issued);
                    return ResponseEntity.ok(issued);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Requirements({"GW_0131"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "Revoke a machine API credential",
            description = "Immediate and permanent, checked at authentication time rather than swept, so it"
                    + " takes effect on the credential's very next request. Audit-logged.")
    @ApiResponse(responseCode = "204", description = "Revoked")
    @ApiResponse(responseCode = "403", description = "The caller does not hold the admin role")
    @ApiResponse(responseCode = "404", description = "No such active machine credential")
    public ResponseEntity<Void> revoke(@PathVariable long id, Authentication authentication) {
        roleService.requireAdminRegardlessOfEnforcement(authentication);
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
                token.machineOwner());
    }
}

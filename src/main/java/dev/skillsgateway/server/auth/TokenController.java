package dev.skillsgateway.server.auth;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.persistence.AccessToken;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tokens")
public class TokenController {

    /** Token events are not tied to a marketplace; the ledger column is NOT NULL. */
    private static final String NO_MARKETPLACE = "-";

    private final TokenService tokenService;
    private final AdminAuditLogger auditLogger;

    public TokenController(TokenService tokenService, AdminAuditLogger auditLogger) {
        this.tokenService = tokenService;
        this.auditLogger = auditLogger;
    }

    @Schema(description = "Token creation request")
    public record CreateTokenRequest(
            @Schema(description = "Human-readable token name", example = "ci-runner")
            String name,

            @Schema(
                    description = "Marketplace names (the catalog's name is valid) the token may"
                            + " fetch; empty or omitted grants every marketplace")
            List<String> scopes,

            @Schema(description = "Expiry; omitted means never, unless a max lifetime is configured")
            Instant expiresAt) {}

    /** Never exposes the stored hash. */
    @Schema(description = "A token without its secret; the cleartext is only returned at creation")
    public record TokenView(
            @Schema(description = "Token id") long id,
            @Schema(description = "Token name") String name,
            @Schema(description = "Creation time") Instant createdAt,

            @Schema(description = "Revocation time, or null while active")
            Instant revokedAt,

            @Schema(description = "Marketplace scopes; empty grants every marketplace")
            List<String> scopes,

            @Schema(description = "Expiry, or null for a token that never expires")
            Instant expiresAt,

            @Schema(description = "The token this one replaced by rotation, or null")
            Long rotatedFrom) {}

    @PostMapping
    @Requirements({"GW_0067"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "Create a token",
            description = "Issues a personal access token for the git facade. Optionally scoped to named"
                    + " marketplaces (unscoped grants all) and optionally expiring; the cleartext is returned"
                    + " exactly once. Creation is audit-logged with the token's name and scopes.")
    @ApiResponse(responseCode = "201", description = "Token issued; the only response carrying the cleartext")
    @ApiResponse(responseCode = "422", description = "Unknown scope, or lifetime beyond the configured cap")
    public ResponseEntity<TokenService.IssuedToken> create(
            @RequestBody CreateTokenRequest request, Authentication authentication) {
        TokenService.IssuedToken issued = tokenService.create(
                authentication.getName(),
                request.name(),
                request.scopes() == null ? List.of() : request.scopes(),
                request.expiresAt());
        audit(authentication, "token-created", issued.id(), issued.name(), issued.scopes());
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }

    @GetMapping
    @Tag(name = "Tokens")
    @Operation(summary = "List your tokens", description = "Only the caller's own tokens, never any secret.")
    public List<TokenView> list(Authentication authentication) {
        return tokenService.list(authentication.getName()).stream()
                .map(TokenController::view)
                .toList();
    }

    @PostMapping("/{id}/rotate")
    @Requirements({"GW_0066", "GW_0067"})
    @Tag(name = "Tokens")
    @Operation(
            summary = "Rotate a token",
            description = "Issues a fresh secret with the identical grant — name, scopes, and the same expiry"
                    + " deadline — and revokes the old token first, so no moment has two live secrets. Only the"
                    + " owner may rotate, only a live token can be rotated, and the new cleartext is returned"
                    + " exactly once.")
    @ApiResponse(responseCode = "200", description = "Rotated; the only response carrying the new cleartext")
    @ApiResponse(responseCode = "404", description = "No such token of yours")
    @ApiResponse(responseCode = "409", description = "Token is revoked or expired; issue a new one instead")
    public ResponseEntity<TokenService.IssuedToken> rotate(@PathVariable long id, Authentication authentication) {
        return tokenService
                .rotate(id, authentication.getName())
                .map(issued -> {
                    audit(authentication, "token-rotated", issued.id(), issued.name(), issued.scopes());
                    return ResponseEntity.ok(issued);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Requirements({"GW_0067"})
    @Tag(name = "Tokens")
    @Operation(summary = "Revoke a token", description = "Immediate and permanent; audit-logged.")
    @ApiResponse(responseCode = "204", description = "Revoked")
    @ApiResponse(responseCode = "404", description = "No such active token of yours")
    public ResponseEntity<Void> revoke(@PathVariable long id, Authentication authentication) {
        AccessToken token = tokenService.list(authentication.getName()).stream()
                .filter(candidate -> candidate.id() == id)
                .findFirst()
                .orElse(null);
        if (!tokenService.revoke(id, authentication.getName())) {
            return ResponseEntity.notFound().build();
        }
        audit(
                authentication,
                "token-revoked",
                id,
                token == null ? null : token.name(),
                token == null ? List.of() : token.scopeList());
        return ResponseEntity.noContent().build();
    }

    /** Lifecycle entries carry the token's identity, name and scopes (GW_0067). */
    private void audit(Authentication authentication, String event, long tokenId, String name, List<String> scopes) {
        String detail = "token %d '%s' scopes=%s".formatted(tokenId, name, scopes.isEmpty() ? "all" : scopes);
        auditLogger.record(authentication.getName(), NO_MARKETPLACE, event, null, detail);
    }

    private static TokenView view(AccessToken token) {
        return new TokenView(
                token.id(),
                token.name(),
                token.createdAt(),
                token.revokedAt(),
                token.scopeList(),
                token.expiresAt(),
                token.rotatedFrom());
    }

    @ExceptionHandler(TokenService.InvalidTokenRequestException.class)
    public ProblemDetail invalidRequest(TokenService.InvalidTokenRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(TokenService.TokenNotRotatableException.class)
    public ProblemDetail notRotatable(TokenService.TokenNotRotatableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}

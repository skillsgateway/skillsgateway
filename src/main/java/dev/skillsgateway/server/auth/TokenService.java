package dev.skillsgateway.server.auth;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.AccessToken;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.TokenRepository;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private static final String TOKEN_PREFIX = "sgw_";

    private final TokenRepository tokenRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final SkillsGatewayProperties properties;
    private final SecureRandom random = new SecureRandom();

    public TokenService(
            TokenRepository tokenRepository,
            MarketplaceRepository marketplaceRepository,
            SkillsGatewayProperties properties) {
        this.tokenRepository = tokenRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.properties = properties;
    }

    @Schema(description = "A freshly issued token; the only time the cleartext is ever returned")
    public record IssuedToken(
            @Schema(description = "Token id") long id,
            @Schema(description = "Token name") String name,

            @Schema(description = "Cleartext token value - shown exactly once, only a hash is stored")
            String token,

            @Schema(description = "Creation time") Instant createdAt,

            @Schema(description = "Marketplace scopes; empty grants every marketplace")
            List<String> scopes,

            @Schema(description = "Expiry, or null for a token that never expires")
            Instant expiresAt,

            @Schema(description = "The token this one replaced by rotation, or null")
            Long rotatedFrom,

            @Schema(description = "Hosted marketplaces this token may publish to; empty grants none")
            List<String> pushScopes) {}

    /** A scope or lifetime the policy refuses; surfaces as 422. */
    public static class InvalidTokenRequestException extends RuntimeException {
        public InvalidTokenRequestException(String message) {
            super(message);
        }
    }

    /** Rotation of a token that is not live (revoked or expired); surfaces as 409. */
    public static class TokenNotRotatableException extends RuntimeException {
        public TokenNotRotatableException(String message) {
            super(message);
        }
    }

    public IssuedToken create(String principal, String name) {
        return create(principal, name, List.of(), null);
    }

    /**
     * Only the SHA-256 of the token is stored; the cleartext is returned exactly once. Unsalted,
     * unstretched SHA-256 is the documented design for these high-entropy random tokens — they are
     * not passwords.
     *
     * <p>Scopes are validated against the registered marketplaces and the catalog name (GW_0064):
     * a typo'd scope must fail loudly at issue time, not silently never match. A lifetime beyond
     * the configured cap is refused, never clamped (GW_0065).
     */
    @Requirements({"GW_0013", "GW_0064", "GW_0065"})
    public IssuedToken create(String principal, String name, List<String> scopes, Instant expiresAt) {
        return create(principal, name, scopes, expiresAt, List.of());
    }

    /**
     * As above, plus push scopes (GW_0102). A push scope must name a registered <em>hosted</em>
     * marketplace: the catalog is generated, an upstream marketplace's content comes from its
     * upstream, and neither can be published to — so naming one is a mistake worth failing at
     * issue time rather than a grant that could never match.
     */
    @Requirements({"GW_0013", "GW_0064", "GW_0065", "GW_0102"})
    public IssuedToken create(
            String principal, String name, List<String> scopes, Instant expiresAt, List<String> pushScopes) {
        String storedScopes = validateScopes(scopes);
        String storedPushScopes = validatePushScopes(pushScopes);
        validateTtl(expiresAt);
        String secret = newSecret();
        AccessToken stored = tokenRepository.create(
                principal, name, sha256Hex(secret), storedScopes, expiresAt, null, storedPushScopes);
        return issued(stored, secret);
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public boolean revoke(long id, String principal) {
        return tokenRepository.revoke(id, principal);
    }

    /**
     * Same grant, new secret (GW_0066): name, scopes and the same expiry deadline are copied;
     * the old token is revoked before the new one is issued, so a crash between the steps leaves
     * no live secret rather than two. Only the owner rotates, and only a live token: a revoked or
     * expired grant is not a template for a new one.
     */
    @Requirements({"GW_0066"})
    public Optional<IssuedToken> rotate(long id, String principal) {
        Optional<AccessToken> found = tokenRepository.findByIdAndPrincipal(id, principal);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AccessToken old = found.get();
        if (old.revokedAt() != null
                || (old.expiresAt() != null && !old.expiresAt().isAfter(Instant.now()))) {
            throw new TokenNotRotatableException("token %d is not live; issue a new token instead".formatted(id));
        }
        if (!tokenRepository.revoke(id, principal)) {
            throw new TokenNotRotatableException("token %d is not live; issue a new token instead".formatted(id));
        }
        String secret = newSecret();
        AccessToken stored = tokenRepository.create(
                principal,
                old.name(),
                sha256Hex(secret),
                old.scopes(),
                old.expiresAt(),
                id,
                // Rotation changes the secret and nothing else (GW_0066), push scopes included.
                old.pushScopes());
        return Optional.of(issued(stored, secret));
    }

    public List<AccessToken> list(String principal) {
        return tokenRepository.listByPrincipal(principal);
    }

    @Requirements({"GW_0065"})
    public Optional<AccessToken> authenticate(String presentedToken) {
        return tokenRepository.findActiveByHash(sha256Hex(presentedToken));
    }

    private String validateScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return null;
        }
        Set<String> known = new LinkedHashSet<>();
        marketplaceRepository.list().forEach(marketplace -> known.add(marketplace.name()));
        known.add(properties.catalog().name());
        for (String scope : scopes) {
            if (!known.contains(scope)) {
                throw new InvalidTokenRequestException(
                        "unknown scope '%s': scopes name registered marketplaces or the catalog".formatted(scope));
            }
        }
        return String.join(",", new LinkedHashSet<>(scopes));
    }

    @Requirements({"GW_0102"})
    private String validatePushScopes(List<String> pushScopes) {
        if (pushScopes == null || pushScopes.isEmpty()) {
            return null;
        }
        Set<String> hosted = new LinkedHashSet<>();
        marketplaceRepository.list().stream()
                .filter(dev.skillsgateway.server.persistence.Marketplace::hosted)
                .forEach(marketplace -> hosted.add(marketplace.name()));
        for (String scope : pushScopes) {
            if (!hosted.contains(scope)) {
                throw new InvalidTokenRequestException(
                        "unknown push scope '%s': push scopes name registered hosted marketplaces".formatted(scope));
            }
        }
        return String.join(",", new LinkedHashSet<>(pushScopes));
    }

    private void validateTtl(Instant expiresAt) {
        var maxTtl = properties.tokens().maxTtl();
        if (maxTtl == null) {
            return;
        }
        if (expiresAt == null || expiresAt.isAfter(Instant.now().plus(maxTtl))) {
            throw new InvalidTokenRequestException(
                    "token lifetime is capped at %s; an expiry within that window is required".formatted(maxTtl));
        }
    }

    private static IssuedToken issued(AccessToken stored, String secret) {
        return new IssuedToken(
                stored.id(),
                stored.name(),
                secret,
                stored.createdAt(),
                stored.scopeList(),
                stored.expiresAt(),
                stored.rotatedFrom(),
                stored.pushScopeList());
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

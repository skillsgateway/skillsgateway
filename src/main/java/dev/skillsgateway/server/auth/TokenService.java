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
import java.time.Duration;
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
            List<String> pushScopes,

            @Schema(description = "Whether this credential was derived from a browser session (GW_AUTH_0018)")
            boolean sessionDerived,

            @Schema(
                    description = "Administrative API scopes this credential may exercise; empty grants"
                            + " no administrative reach at all")
            List<String> apiScopes,

            @Schema(description = "The identity that provisioned a machine credential, or null")
            String machineOwner) {}

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
     * <p>Scopes are validated against the registered marketplaces and the catalog name (GW_AUTH_0006):
     * a typo'd scope must fail loudly at issue time, not silently never match. A lifetime beyond
     * the configured cap is refused, never clamped (GW_AUTH_0007).
     */
    @Requirements({"GW_AUTH_0004", "GW_AUTH_0006", "GW_AUTH_0007"})
    public IssuedToken create(String principal, String name, List<String> scopes, Instant expiresAt) {
        return create(principal, name, scopes, expiresAt, List.of());
    }

    /**
     * As above, plus push scopes (GW_FACADE_0007). A push scope must name a registered <em>hosted</em>
     * marketplace: the catalog is generated, an upstream marketplace's content comes from its
     * upstream, and neither can be published to — so naming one is a mistake worth failing at
     * issue time rather than a grant that could never match.
     */
    @Requirements({"GW_AUTH_0004", "GW_AUTH_0006", "GW_AUTH_0007", "GW_FACADE_0007"})
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

    /**
     * A git credential derived from the caller's browser session (GW_AUTH_0018). The lifetime is the
     * gateway's, taken from configuration: there is no parameter for it, because a credential
     * whose life the holder can choose is a personal access token reached through another URL.
     * No push scopes, ever — publishing is done with something somebody provisioned on purpose.
     */
    @Requirements({"GW_AUTH_0018"})
    public IssuedToken createSessionCredential(String principal, String name, List<String> scopes) {
        String storedScopes = validateScopes(scopes);
        Instant expiresAt = Instant.now().plus(properties.tokens().sessionTtl());
        String secret = newSecret();
        AccessToken stored =
                tokenRepository.create(principal, name, sha256Hex(secret), storedScopes, expiresAt, null, null, true);
        return issued(stored, secret);
    }

    /**
     * Issues a machine API credential (GW_AUTH_0020, GW_AUTH_0024). Everything here is a refusal rather
     * than a default, because every default this method could offer is a weaker credential than
     * the caller asked for:
     *
     * <ul>
     *   <li>Every administrative scope value is checked against the registry, so a misspelling is
     *       a 422 at issue time rather than a grant that silently never matches — exactly as
     *       fetch scopes already behave. There is no wildcard value to spell.
     *   <li>An empty scope list is refused: a machine credential with no administrative scope is
     *       not a credential, it is a fetch token, and issuing one here would quietly produce
     *       something that cannot do what the caller asked for.
     *   <li>An expiry is required and is never defaulted, and a lifetime beyond the cap is
     *       refused rather than clamped — the posture GW_AUTH_0007 already takes. The cap applies even
     *       when the deployment configures none; see {@code Tokens.DEFAULT_MACHINE_MAX_TTL}.
     *   <li>A session-derived credential can never hold administrative scope. It is minted from a
     *       browser session with a lifetime the holder did not choose, for fetching; letting one
     *       carry control-plane authority would launder a session into a standing credential.
     * </ul>
     */
    @Requirements({"GW_AUTH_0020", "GW_AUTH_0021", "GW_AUTH_0024"})
    public IssuedToken createMachineCredential(
            String principal, String name, List<String> apiScopes, Instant expiresAt, String owner) {
        String storedApiScopes = validateApiScopes(apiScopes);
        validateMachineTtl(expiresAt);
        String secret = newSecret();
        AccessToken stored = tokenRepository.create(
                principal, name, sha256Hex(secret), null, expiresAt, null, null, false, storedApiScopes, owner);
        return issued(stored, secret);
    }

    /**
     * Rotation for a machine credential (GW_AUTH_0024): the same grant with a new secret. The identity,
     * the expiry <em>deadline</em> and all three scope dimensions carry over, and the old
     * credential is revoked before the new one is issued, so no moment has two live secrets. A
     * rotation that silently widened or dropped an administrative scope would be the worst defect
     * this feature could have, which is why the test asserts it per scope value.
     *
     * <p>Administered by id alone rather than by the caller's principal: a machine credential's
     * principal is not an identity anybody logs in as, so owner-scoping would leave it
     * unrotatable by everyone.
     */
    @Requirements({"GW_AUTH_0008", "GW_AUTH_0024"})
    public Optional<IssuedToken> rotateMachineCredential(long id) {
        Optional<AccessToken> found = tokenRepository.findById(id).filter(AccessToken::machineCredential);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AccessToken old = found.get();
        if (old.revokedAt() != null
                || (old.expiresAt() != null && !old.expiresAt().isAfter(Instant.now()))) {
            throw new TokenNotRotatableException("token %d is not live; issue a new token instead".formatted(id));
        }
        if (!tokenRepository.revoke(id)) {
            throw new TokenNotRotatableException("token %d is not live; issue a new token instead".formatted(id));
        }
        String secret = newSecret();
        AccessToken stored = tokenRepository.create(
                old.principal(),
                old.name(),
                sha256Hex(secret),
                old.scopes(),
                old.expiresAt(),
                id,
                old.pushScopes(),
                old.sessionDerived(),
                old.apiScopes(),
                old.machineOwner());
        return Optional.of(issued(stored, secret));
    }

    /**
     * Every machine credential, whoever provisioned it (GW_AUTH_0024). The caller's own-token listing
     * keeps its strict own-principal scoping; this is the separate administrative view, because a
     * credential nobody can see is a credential nobody can revoke during an incident.
     */
    @Requirements({"GW_AUTH_0024"})
    public List<AccessToken> listMachineCredentials() {
        return tokenRepository.listMachineCredentials();
    }

    /** Administrative revocation of a machine credential; takes effect at the next lookup. */
    @Requirements({"GW_AUTH_0024"})
    public boolean revokeMachineCredential(long id) {
        return tokenRepository
                .findById(id)
                .filter(AccessToken::machineCredential)
                .map(token -> tokenRepository.revoke(id))
                .orElse(false);
    }

    /** A machine credential by id, for the administrative listing and the ledger detail. */
    public Optional<AccessToken> findMachineCredential(long id) {
        return tokenRepository.findById(id).filter(AccessToken::machineCredential);
    }

    @Requirements({"GW_AUTH_0020"})
    private String validateApiScopes(List<String> apiScopes) {
        if (apiScopes == null || apiScopes.isEmpty()) {
            throw new InvalidTokenRequestException("a machine credential must name at least one API scope;"
                    + " there is no value that grants every scope");
        }
        for (String scope : apiScopes) {
            if (!MachineApiRegistry.isKnownScope(scope)) {
                throw new InvalidTokenRequestException(
                        "unknown API scope '%s': API scopes are named per concern and there is no wildcard"
                                .formatted(scope));
            }
        }
        return String.join(",", new LinkedHashSet<>(apiScopes));
    }

    @Requirements({"GW_AUTH_0024"})
    private void validateMachineTtl(Instant expiresAt) {
        if (expiresAt == null) {
            throw new InvalidTokenRequestException("a machine credential must state an expiry; it is never defaulted");
        }
        Duration cap = properties.tokens().machineMaxTtl();
        if (expiresAt.isAfter(Instant.now().plus(cap))) {
            throw new InvalidTokenRequestException(
                    "machine credential lifetime is capped at %s; an expiry within that window is required"
                            .formatted(cap));
        }
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
     * Same grant, new secret (GW_AUTH_0008): name, scopes and the same expiry deadline are copied;
     * the old token is revoked before the new one is issued, so a crash between the steps leaves
     * no live secret rather than two. Only the owner rotates, and only a live token: a revoked or
     * expired grant is not a template for a new one.
     */
    @Requirements({"GW_AUTH_0008"})
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
                // Rotation changes the secret and nothing else (GW_AUTH_0008): push scopes, the expiry
                // deadline, and the session-derived mark all carry over — so a rotation can
                // neither widen a grant nor launder a session credential into a standing one.
                old.pushScopes(),
                old.sessionDerived());
        return Optional.of(issued(stored, secret));
    }

    public List<AccessToken> list(String principal) {
        return tokenRepository.listByPrincipal(principal);
    }

    @Requirements({"GW_AUTH_0007"})
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

    @Requirements({"GW_FACADE_0007"})
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
                stored.pushScopeList(),
                stored.sessionDerived(),
                stored.apiScopeList(),
                stored.machineOwner());
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

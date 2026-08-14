package io.github.jimisola.skillsgateway.auth;

import io.github.jimisola.skillsgateway.persistence.AccessToken;
import io.github.jimisola.skillsgateway.persistence.TokenRepository;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private static final String TOKEN_PREFIX = "sgw_";

    private final TokenRepository tokenRepository;
    private final SecureRandom random = new SecureRandom();

    public TokenService(TokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Schema(description = "A freshly issued token; the only time the cleartext is ever returned")
    public record IssuedToken(
            @Schema(description = "Token id") long id,
            @Schema(description = "Token name") String name,

            @Schema(description = "Cleartext token value - shown exactly once, only a hash is stored")
            String token,

            @Schema(description = "Creation time") Instant createdAt) {}

    /**
     * Only the SHA-256 of the token is stored; the cleartext is returned exactly once. Unsalted,
     * unstretched SHA-256 is the documented design for these high-entropy random tokens — they are
     * not passwords.
     */
    @Requirements({"GW_0013"})
    public IssuedToken create(String principal, String name) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AccessToken stored = tokenRepository.create(principal, name, sha256Hex(token));
        return new IssuedToken(stored.id(), stored.name(), token, stored.createdAt());
    }

    public boolean revoke(long id, String principal) {
        return tokenRepository.revoke(id, principal);
    }

    public List<AccessToken> list(String principal) {
        return tokenRepository.listByPrincipal(principal);
    }

    public Optional<AccessToken> authenticate(String presentedToken) {
        return tokenRepository.findActiveByHash(sha256Hex(presentedToken));
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

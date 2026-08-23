package dev.skillsgateway.server.persistence;

import io.github.reqstool.annotations.Requirements;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TokenRepository {

    private final JdbcClient jdbc;

    public TokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AccessToken create(String principal, String name, String tokenHash) {
        return create(principal, name, tokenHash, null, null, null);
    }

    @Requirements({"GW_0064", "GW_0065"})
    public AccessToken create(
            String principal, String name, String tokenHash, String scopes, Instant expiresAt, Long rotatedFrom) {
        return create(principal, name, tokenHash, scopes, expiresAt, rotatedFrom, null);
    }

    @Requirements({"GW_0064", "GW_0065", "GW_0102"})
    public AccessToken create(
            String principal,
            String name,
            String tokenHash,
            String scopes,
            Instant expiresAt,
            Long rotatedFrom,
            String pushScopes) {
        return jdbc.sql("INSERT INTO access_tokens"
                        + " (principal, name, token_hash, created_at, scopes, expires_at, rotated_from,"
                        + " push_scopes)"
                        + " VALUES (:principal, :name, :hash, :now, :scopes, :expiresAt, :rotatedFrom,"
                        + " :pushScopes)"
                        + " RETURNING *")
                .param("pushScopes", pushScopes)
                .param("principal", principal)
                .param("name", name)
                .param("hash", tokenHash)
                .param("now", OffsetDateTime.now())
                .param("scopes", scopes)
                .param("expiresAt", expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC))
                .param("rotatedFrom", rotatedFrom)
                .query(TokenRepository::map)
                .single();
    }

    /**
     * Live tokens only: revoked and expired are both dead. Expiry is a comparison against now at
     * lookup time (GW_0065) — no sweep is involved, so none can be late.
     */
    @Requirements({"GW_0065"})
    public Optional<AccessToken> findActiveByHash(String tokenHash) {
        return jdbc.sql("SELECT * FROM access_tokens WHERE token_hash = :hash AND revoked_at IS NULL"
                        + " AND (expires_at IS NULL OR expires_at > :now)")
                .param("hash", tokenHash)
                .param("now", OffsetDateTime.now())
                .query(TokenRepository::map)
                .optional();
    }

    public Optional<AccessToken> findByIdAndPrincipal(long id, String principal) {
        return jdbc.sql("SELECT * FROM access_tokens WHERE id = :id AND principal = :principal")
                .param("id", id)
                .param("principal", principal)
                .query(TokenRepository::map)
                .optional();
    }

    public List<AccessToken> listByPrincipal(String principal) {
        return jdbc.sql("SELECT * FROM access_tokens WHERE principal = :principal ORDER BY id")
                .param("principal", principal)
                .query(TokenRepository::map)
                .list();
    }

    public boolean revoke(long id, String principal) {
        return jdbc.sql("UPDATE access_tokens SET revoked_at = :now"
                                + " WHERE id = :id AND principal = :principal AND revoked_at IS NULL")
                        .param("now", OffsetDateTime.now())
                        .param("id", id)
                        .param("principal", principal)
                        .update()
                > 0;
    }

    static AccessToken map(ResultSet rs, int rowNum) throws SQLException {
        long rotatedFromValue = rs.getLong("rotated_from");
        Long rotatedFrom = rs.wasNull() ? null : rotatedFromValue;
        return new AccessToken(
                rs.getLong("id"),
                rs.getString("principal"),
                rs.getString("name"),
                rs.getString("token_hash"),
                MarketplaceRepository.instant(rs, "created_at"),
                MarketplaceRepository.instant(rs, "revoked_at"),
                rs.getString("scopes"),
                MarketplaceRepository.instant(rs, "expires_at"),
                rotatedFrom,
                rs.getString("push_scopes"));
    }
}

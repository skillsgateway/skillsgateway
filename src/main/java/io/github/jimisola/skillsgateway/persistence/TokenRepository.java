package io.github.jimisola.skillsgateway.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
        return jdbc.sql("INSERT INTO access_tokens (principal, name, token_hash, created_at)"
                        + " VALUES (:principal, :name, :hash, :now) RETURNING *")
                .param("principal", principal)
                .param("name", name)
                .param("hash", tokenHash)
                .param("now", OffsetDateTime.now())
                .query(TokenRepository::map)
                .single();
    }

    public Optional<AccessToken> findActiveByHash(String tokenHash) {
        return jdbc.sql("SELECT * FROM access_tokens WHERE token_hash = :hash AND revoked_at IS NULL")
                .param("hash", tokenHash)
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
        return new AccessToken(
                rs.getLong("id"),
                rs.getString("principal"),
                rs.getString("name"),
                rs.getString("token_hash"),
                MarketplaceRepository.instant(rs, "created_at"),
                MarketplaceRepository.instant(rs, "revoked_at"));
    }
}

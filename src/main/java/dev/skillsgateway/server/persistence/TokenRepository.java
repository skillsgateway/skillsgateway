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
        return create(principal, name, tokenHash, scopes, expiresAt, rotatedFrom, pushScopes, false);
    }

    @Requirements({"GW_0064", "GW_0065", "GW_0102", "GW_0104"})
    public AccessToken create(
            String principal,
            String name,
            String tokenHash,
            String scopes,
            Instant expiresAt,
            Long rotatedFrom,
            String pushScopes,
            boolean sessionDerived) {
        return create(
                principal, name, tokenHash, scopes, expiresAt, rotatedFrom, pushScopes, sessionDerived, null, null);
    }

    /**
     * As above, plus the administrative scope list and the provisioning identity (GW_0126,
     * GW_0131). A NULL {@code apiScopes} is the pre-change meaning — no administrative reach —
     * which is what every credential that already exists keeps.
     */
    @Requirements({"GW_0064", "GW_0065", "GW_0102", "GW_0104", "GW_0126", "GW_0131"})
    public AccessToken create(
            String principal,
            String name,
            String tokenHash,
            String scopes,
            Instant expiresAt,
            Long rotatedFrom,
            String pushScopes,
            boolean sessionDerived,
            String apiScopes,
            String machineOwner) {
        return jdbc.sql("INSERT INTO access_tokens"
                        + " (principal, name, token_hash, created_at, scopes, expires_at, rotated_from,"
                        + " push_scopes, session_derived, api_scopes, machine_owner)"
                        + " VALUES (:principal, :name, :hash, :now, :scopes, :expiresAt, :rotatedFrom,"
                        + " :pushScopes, :sessionDerived, :apiScopes, :machineOwner)"
                        + " RETURNING *")
                .param("apiScopes", apiScopes)
                .param("machineOwner", machineOwner)
                .param("pushScopes", pushScopes)
                .param("sessionDerived", sessionDerived)
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

    /**
     * Every machine credential, whoever provisioned it (GW_0131). Deliberately not filtered by
     * the caller's principal: a credential's own principal is not a person anyone can log in as,
     * so scoping the listing the way {@link #listByPrincipal} does would leave every machine
     * credential invisible to everyone — nobody could revoke one during an incident.
     */
    @Requirements({"GW_0131"})
    public List<AccessToken> listMachineCredentials() {
        return jdbc.sql("SELECT * FROM access_tokens WHERE api_scopes IS NOT NULL ORDER BY id")
                .query(TokenRepository::map)
                .list();
    }

    /** A single credential by id, unscoped; the administrative paths resolve through this. */
    @Requirements({"GW_0131"})
    public Optional<AccessToken> findById(long id) {
        return jdbc.sql("SELECT * FROM access_tokens WHERE id = :id")
                .param("id", id)
                .query(TokenRepository::map)
                .optional();
    }

    /** Administrative revocation: by id alone, for the reason {@link #listMachineCredentials} gives. */
    @Requirements({"GW_0131"})
    public boolean revoke(long id) {
        return jdbc.sql("UPDATE access_tokens SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                        .param("now", OffsetDateTime.now())
                        .param("id", id)
                        .update()
                > 0;
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
                rs.getString("push_scopes"),
                rs.getBoolean("session_derived"),
                rs.getString("api_scopes"),
                rs.getString("machine_owner"));
    }
}

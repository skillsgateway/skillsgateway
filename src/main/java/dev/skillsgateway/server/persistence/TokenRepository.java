package dev.skillsgateway.server.persistence;

import io.github.reqstool.annotations.Requirements;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TokenRepository {

    /**
     * How stale a credential's recorded last use must be before another successful authentication
     * rewrites it (GW_AUTH_0031). A constant rather than a setting: nothing reads the value at finer
     * resolution, and an auditor who needs the exact record has the append-only fetch ledger.
     */
    private static final Duration LAST_USED_WRITE_INTERVAL = Duration.ofMinutes(1);

    private final JdbcClient jdbc;

    public TokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AccessToken create(String principal, String name, String tokenHash) {
        return create(principal, name, tokenHash, null, null, null);
    }

    @Requirements({"GW_AUTH_0006", "GW_AUTH_0007"})
    public AccessToken create(
            String principal, String name, String tokenHash, List<String> scopes, Instant expiresAt, Long rotatedFrom) {
        return create(principal, name, tokenHash, scopes, expiresAt, rotatedFrom, null);
    }

    @Requirements({"GW_AUTH_0006", "GW_AUTH_0007", "GW_FACADE_0007"})
    public AccessToken create(
            String principal,
            String name,
            String tokenHash,
            List<String> scopes,
            Instant expiresAt,
            Long rotatedFrom,
            List<String> pushScopes) {
        return create(principal, name, tokenHash, scopes, expiresAt, rotatedFrom, pushScopes, false);
    }

    @Requirements({"GW_AUTH_0006", "GW_AUTH_0007", "GW_FACADE_0007", "GW_AUTH_0018"})
    public AccessToken create(
            String principal,
            String name,
            String tokenHash,
            List<String> scopes,
            Instant expiresAt,
            Long rotatedFrom,
            List<String> pushScopes,
            boolean sessionDerived) {
        return create(
                principal, name, tokenHash, scopes, expiresAt, rotatedFrom, pushScopes, sessionDerived, null, null);
    }

    /**
     * As above, plus the administrative scope list and the provisioning identity (GW_AUTH_0020,
     * GW_AUTH_0024). A NULL {@code apiScopes} is the pre-change meaning — no administrative reach —
     * which is what every credential that already exists keeps.
     */
    @Requirements({"GW_AUTH_0006", "GW_AUTH_0007", "GW_FACADE_0007", "GW_AUTH_0018", "GW_AUTH_0020", "GW_AUTH_0024"})
    public AccessToken create(
            String principal,
            String name,
            String tokenHash,
            List<String> scopes,
            Instant expiresAt,
            Long rotatedFrom,
            List<String> pushScopes,
            boolean sessionDerived,
            List<String> apiScopes,
            String machineOwner) {
        return jdbc.sql("INSERT INTO access_tokens"
                        + " (principal, name, token_hash, created_at, scopes, expires_at, rotated_from,"
                        + " push_scopes, session_derived, api_scopes, machine_owner)"
                        + " VALUES (:principal, :name, :hash, :now, :scopes::text[], :expiresAt, :rotatedFrom,"
                        + " :pushScopes::text[], :sessionDerived, :apiScopes::text[], :machineOwner)"
                        + " RETURNING *")
                .param("apiScopes", SqlArrays.literal(apiScopes))
                .param("machineOwner", machineOwner)
                .param("pushScopes", SqlArrays.literal(pushScopes))
                .param("sessionDerived", sessionDerived)
                .param("principal", principal)
                .param("name", name)
                .param("hash", tokenHash)
                .param("now", OffsetDateTime.now())
                .param("scopes", SqlArrays.literal(scopes))
                .param("expiresAt", expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC))
                .param("rotatedFrom", rotatedFrom)
                .query(TokenRepository::map)
                .single();
    }

    /**
     * Live tokens only: revoked and expired are both dead. Expiry is a comparison against now at
     * lookup time (GW_AUTH_0007) — no sweep is involved, so none can be late.
     */
    @Requirements({"GW_AUTH_0007"})
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
     * Every machine credential, whoever provisioned it (GW_AUTH_0024). Deliberately not filtered by
     * the caller's principal: a credential's own principal is not a person anyone can log in as,
     * so scoping the listing the way {@link #listByPrincipal} does would leave every machine
     * credential invisible to everyone — nobody could revoke one during an incident.
     */
    @Requirements({"GW_AUTH_0024"})
    public List<AccessToken> listMachineCredentials() {
        return jdbc.sql("SELECT * FROM access_tokens WHERE api_scopes IS NOT NULL ORDER BY id")
                .query(TokenRepository::map)
                .list();
    }

    /** A single credential by id, unscoped; the administrative paths resolve through this. */
    @Requirements({"GW_AUTH_0024"})
    public Optional<AccessToken> findById(long id) {
        return jdbc.sql("SELECT * FROM access_tokens WHERE id = :id")
                .param("id", id)
                .query(TokenRepository::map)
                .optional();
    }

    /** Administrative revocation: by id alone, for the reason {@link #listMachineCredentials} gives. */
    @Requirements({"GW_AUTH_0024"})
    public boolean revoke(long id) {
        return jdbc.sql("UPDATE access_tokens SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                        .param("now", OffsetDateTime.now())
                        .param("id", id)
                        .update()
                > 0;
    }

    /**
     * Stamps the moment a credential authenticated successfully (GW_AUTH_0031), at most once per
     * {@link #LAST_USED_WRITE_INTERVAL}.
     *
     * <p>The throttle is the statement's own WHERE clause rather than a read-then-write or an
     * in-memory cache: there is no lost-update window — concurrent requests for one credential race
     * on one row and the loser writes nothing — and no per-instance state that would make a
     * multi-replica gateway write once per minute <em>each</em>. A busy credential therefore costs
     * one statement that matches no row per request.
     *
     * <p>Only ever called once a chain has decided to authenticate; see
     * {@code TokenService#recordUse}.
     */
    @Requirements({"GW_AUTH_0031"})
    public void recordLastUsed(long id) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("UPDATE access_tokens SET last_used_at = :now"
                        + " WHERE id = :id AND (last_used_at IS NULL OR last_used_at <= :threshold)")
                .param("now", now)
                .param("id", id)
                .param("threshold", now.minus(LAST_USED_WRITE_INTERVAL))
                .update();
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

    /**
     * Explicit mapping because three columns are {@code TEXT[]}: the automatic record mapper cannot
     * turn a {@link java.sql.Array} into a {@code List<String>}. NULL stays null rather than
     * becoming an empty list — on {@code scopes} those mean opposite things (GW_AUTH_0006), and the
     * schema refuses the empty array so that only one of them is ever representable.
     */
    private static AccessToken map(ResultSet rs, int rowNum) throws SQLException {
        return new AccessToken(
                rs.getLong("id"),
                rs.getString("principal"),
                rs.getString("name"),
                rs.getString("token_hash"),
                instant(rs, "created_at"),
                instant(rs, "revoked_at"),
                SqlArrays.read(rs, "scopes"),
                instant(rs, "expires_at"),
                nullableLong(rs, "rotated_from"),
                SqlArrays.read(rs, "push_scopes"),
                rs.getBoolean("session_derived"),
                SqlArrays.read(rs, "api_scopes"),
                rs.getString("machine_owner"),
                instant(rs, "last_used_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}

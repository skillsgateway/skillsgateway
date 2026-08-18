package dev.skillsgateway.server.policy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence of policy deny rules (GW_0089). */
@Repository
public class PolicyRuleRepository {

    private final JdbcClient jdbc;

    public PolicyRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public PolicyRule create(String name, String description, String expression, boolean enabled, String actor) {
        return jdbc.sql("INSERT INTO policy_rules (name, description, expression, enabled, created_by, created_at)"
                        + " VALUES (:name, :description, :expression, :enabled, :actor, :now) RETURNING *")
                .param("name", name)
                .param("description", description)
                .param("expression", expression)
                .param("enabled", enabled)
                .param("actor", actor)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .query(PolicyRuleRepository::map)
                .single();
    }

    public PolicyRule update(long id, String description, String expression, boolean enabled, String actor) {
        return jdbc.sql("UPDATE policy_rules SET description = :description, expression = :expression,"
                        + " enabled = :enabled, updated_by = :actor, updated_at = :now WHERE id = :id RETURNING *")
                .param("id", id)
                .param("description", description)
                .param("expression", expression)
                .param("enabled", enabled)
                .param("actor", actor)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .query(PolicyRuleRepository::map)
                .single();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM policy_rules WHERE id = :id").param("id", id).update();
    }

    public Optional<PolicyRule> findByName(String name) {
        return jdbc.sql("SELECT * FROM policy_rules WHERE name = :name")
                .param("name", name)
                .query(PolicyRuleRepository::map)
                .optional();
    }

    public List<PolicyRule> list() {
        return jdbc.sql("SELECT * FROM policy_rules ORDER BY name")
                .query(PolicyRuleRepository::map)
                .list();
    }

    /** The gate's only query: every rule that may decide an approval right now (GW_0090). */
    public List<PolicyRule> listEnabled() {
        return jdbc.sql("SELECT * FROM policy_rules WHERE enabled ORDER BY name")
                .query(PolicyRuleRepository::map)
                .list();
    }

    static PolicyRule map(ResultSet rs, int rowNum) throws SQLException {
        return new PolicyRule(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("expression"),
                rs.getBoolean("enabled"),
                rs.getString("created_by"),
                instant(rs, "created_at"),
                rs.getString("updated_by"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

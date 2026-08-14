package io.github.jimisola.skillsgateway.persistence;

import io.github.reqstool.annotations.Requirements;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MarketplaceRepository {

    private final JdbcClient jdbc;

    public MarketplaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Requirements({"GW_0001"})
    public Marketplace register(String name, String url) {
        return jdbc.sql("INSERT INTO marketplaces (name, url, created_at) VALUES (:name, :url, :now)" + " RETURNING *")
                .param("name", name)
                .param("url", url)
                .param("now", OffsetDateTime.now())
                .query(MarketplaceRepository::map)
                .single();
    }

    public Optional<Marketplace> findByName(String name) {
        return jdbc.sql("SELECT * FROM marketplaces WHERE name = :name")
                .param("name", name)
                .query(MarketplaceRepository::map)
                .optional();
    }

    public Optional<Marketplace> findById(long id) {
        return jdbc.sql("SELECT * FROM marketplaces WHERE id = :id")
                .param("id", id)
                .query(MarketplaceRepository::map)
                .optional();
    }

    public List<Marketplace> list() {
        return jdbc.sql("SELECT * FROM marketplaces ORDER BY name")
                .query(MarketplaceRepository::map)
                .list();
    }

    static Marketplace map(ResultSet rs, int rowNum) throws SQLException {
        return new Marketplace(rs.getLong("id"), rs.getString("name"), rs.getString("url"), instant(rs, "created_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

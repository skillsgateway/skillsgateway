package dev.skillsgateway.server.policy;

import io.github.reqstool.annotations.Requirements;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The recorded facts of each snapshot and the plugin-name index beside them (GW_INGEST_0036).
 * Insert-once: nothing here updates a row, so a snapshot's record is whatever was first written.
 */
@Repository
public class SnapshotFactsRepository {

    private final JdbcClient jdbc;

    public SnapshotFactsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One plugin a manifest declares, and the manifest location that declares it. */
    public record PluginName(String name, String location) {}

    /** A plugin name somewhere in the approved estate, with the snapshot that carries it. */
    public record EstateName(long snapshotId, long marketplaceId, String marketplace, String name, String location) {}

    /**
     * Writes the record and its index in one transaction, or nothing when the snapshot already has
     * one — a concurrent writer of the same commit produces the same record, so losing the race loses
     * nothing.
     *
     * @param factsJson the facts without the snapshot's state, or null when they could not be built
     * @return whether this call wrote the record
     */
    @Requirements({"GW_INGEST_0036"})
    @Transactional
    public boolean insertOnce(long snapshotId, String factsJson, List<PluginName> plugins) {
        int inserted = jdbc.sql("INSERT INTO snapshot_facts (snapshot_id, facts, built_at)"
                        + " VALUES (:id, CAST(:facts AS jsonb), :now) ON CONFLICT (snapshot_id) DO NOTHING")
                .param("id", snapshotId)
                .param("facts", factsJson)
                .param("now", OffsetDateTime.now())
                .update();
        if (inserted == 0) {
            return false;
        }
        for (PluginName plugin : plugins) {
            jdbc.sql("INSERT INTO snapshot_plugin_names (snapshot_id, plugin_name, location)"
                            + " VALUES (:id, :name, :location)")
                    .param("id", snapshotId)
                    .param("name", plugin.name())
                    .param("location", plugin.location())
                    .update();
        }
        return true;
    }

    /** Whether the snapshot has a record, which is what makes its plugin-name index complete. */
    public boolean indexed(long snapshotId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM snapshot_facts WHERE snapshot_id = :id)")
                .param("id", snapshotId)
                .query(Boolean.class)
                .single();
    }

    /** A snapshot's record: {@code factsJson} is null when the facts could not be built at ingestion. */
    public record Recorded(String factsJson) {}

    /** The snapshot's record, or empty when it has none. */
    public Optional<Recorded> find(long snapshotId) {
        return jdbc.sql("SELECT facts::text AS facts FROM snapshot_facts WHERE snapshot_id = :id")
                .param("id", snapshotId)
                .query((rs, row) -> new Recorded(rs.getString("facts")))
                .optional();
    }

    public List<PluginName> pluginNames(long snapshotId) {
        return jdbc.sql("SELECT plugin_name, location FROM snapshot_plugin_names WHERE snapshot_id = :id"
                        + " ORDER BY location, plugin_name")
                .param("id", snapshotId)
                .query((rs, row) -> new PluginName(rs.getString("plugin_name"), rs.getString("location")))
                .list();
    }

    /**
     * Every plugin name of every approved, non-deleted snapshot other than {@code excludingSnapshotId}
     * — the estate the name-collision gate reads (GW_APPROVAL_0019.2). The caller splits it into the
     * snapshot's own marketplace's history and everyone else's.
     */
    @Requirements({"GW_APPROVAL_0019.2"})
    public List<EstateName> approvedEstate(long excludingSnapshotId) {
        return jdbc.sql("SELECT s.id AS snapshot_id, s.marketplace_id, m.name AS marketplace, n.plugin_name,"
                        + " n.location FROM snapshot_plugin_names n"
                        + " JOIN snapshots s ON s.id = n.snapshot_id"
                        + " JOIN marketplaces m ON m.id = s.marketplace_id"
                        + " WHERE s.state = 'approved' AND s.deleted_at IS NULL AND s.id <> :excluding"
                        + " ORDER BY s.id, n.location")
                .param("excluding", excludingSnapshotId)
                .query((rs, row) -> new EstateName(
                        rs.getLong("snapshot_id"),
                        rs.getLong("marketplace_id"),
                        rs.getString("marketplace"),
                        rs.getString("plugin_name"),
                        rs.getString("location")))
                .list();
    }
}

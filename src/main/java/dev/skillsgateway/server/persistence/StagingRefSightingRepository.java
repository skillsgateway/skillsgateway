package dev.skillsgateway.server.persistence;

import io.github.reqstool.annotations.Requirements;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * How long the gateway has been looking at a publication staging reference (GW_0168).
 *
 * <p>The retention sweep may only remove a staging reference that has outlived a bound on how long
 * a publication can take, and a git reference carries no creation time either backend can be asked
 * for. So the gateway keeps its own: the first pass that sees a reference stamps it, and the answer
 * to "how old is this" is "at least as old as that stamp" — an under-estimate, which is the only
 * direction it is safe to be wrong in. Under-estimating delays a sweep; over-estimating would walk
 * it into a publication that is still running.
 *
 * <p>Marketplaces are named rather than referenced: the sweep enumerates published repositories off
 * the storage backend, and a repository whose marketplace row has gone still has to be swept.
 */
@Repository
public class StagingRefSightingRepository {

    private final JdbcClient jdbc;

    public StagingRefSightingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records {@code at} as the first sighting of every reference not seen before, forgets the ones
     * that are no longer there, and answers with the first sighting of each reference given.
     *
     * <p>One transaction, because the two halves are one statement about what this marketplace's
     * staging namespace held at this instant. Forgetting is what keeps a reference that a
     * publication completed — and a later one re-staged under the same name — from inheriting the
     * age of the first: the row goes when the reference does, so the clock restarts.
     *
     * @param at the observation instant, which becomes the stamp of anything new
     */
    @Requirements({"GW_0168"})
    @Transactional
    public Map<String, Instant> observe(String marketplace, Collection<String> refs, Instant at) {
        if (refs.isEmpty()) {
            jdbc.sql("DELETE FROM staging_ref_sightings WHERE marketplace = :marketplace")
                    .param("marketplace", marketplace)
                    .update();
            return Map.of();
        }
        List<String> present = List.copyOf(refs);
        jdbc.sql("DELETE FROM staging_ref_sightings WHERE marketplace = :marketplace AND ref NOT IN (:refs)")
                .param("marketplace", marketplace)
                .param("refs", present)
                .update();
        for (String ref : present) {
            jdbc.sql("INSERT INTO staging_ref_sightings (marketplace, ref, first_seen_at)"
                            + " VALUES (:marketplace, :ref, :at) ON CONFLICT (marketplace, ref) DO NOTHING")
                    .param("marketplace", marketplace)
                    .param("ref", ref)
                    .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                    .update();
        }
        Map<String, Instant> firstSeen = new HashMap<>();
        jdbc.sql("SELECT ref, first_seen_at FROM staging_ref_sightings"
                        + " WHERE marketplace = :marketplace AND ref IN (:refs)")
                .param("marketplace", marketplace)
                .param("refs", present)
                .query((rs, rowNum) -> Map.entry(
                        rs.getString("ref"),
                        rs.getObject("first_seen_at", OffsetDateTime.class).toInstant()))
                .list()
                .forEach(entry -> firstSeen.put(entry.getKey(), entry.getValue()));
        return firstSeen;
    }

    /** Forgets the references a sweep has just removed, so nothing outlives what it describes. */
    @Requirements({"GW_0168"})
    public void forget(String marketplace, Collection<String> refs) {
        if (refs.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM staging_ref_sightings WHERE marketplace = :marketplace AND ref IN (:refs)")
                .param("marketplace", marketplace)
                .param("refs", List.copyOf(refs))
                .update();
    }
}

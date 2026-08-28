package dev.skillsgateway.server.persistence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only fetch ledger: this repository intentionally offers no update or delete. */
@Repository
public class FetchLogRepository {

    private final JdbcClient jdbc;

    public FetchLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String source, String principal, String marketplace, String event, String ref, String sha) {
        append(source, principal, marketplace, event, ref, sha, null);
    }

    /** As {@link #append}, with the free-text qualifier the entry needs (an override reason). */
    public void append(
            String source, String principal, String marketplace, String event, String ref, String sha, String detail) {
        append(source, principal, marketplace, event, ref, sha, detail, null);
    }

    /** As {@link #append}, attributing a facade entry to the token that authenticated it (GW_0067). */
    public void append(
            String source,
            String principal,
            String marketplace,
            String event,
            String ref,
            String sha,
            String detail,
            Long tokenId) {
        append(source, principal, marketplace, event, ref, sha, detail, tokenId, ActorType.HUMAN);
    }

    /**
     * As {@link #append}, typing the actor explicitly (GW_0128). The value is written through a
     * cast to the {@code fetch_log_actor_type} enum, so a value outside the set is a type error
     * in the database rather than a string nobody checked.
     */
    public void append(
            String source,
            String principal,
            String marketplace,
            String event,
            String ref,
            String sha,
            String detail,
            Long tokenId,
            ActorType actorType) {
        jdbc.sql("INSERT INTO fetch_log"
                        + " (ts, source, principal, marketplace, event, ref, sha, detail, token_id, actor_type)"
                        + " VALUES (:now, :source, :principal, :marketplace, :event, :ref, :sha, :detail, :tokenId,"
                        + " :actorType::fetch_log_actor_type)")
                .param("actorType", actorType.value())
                .param("now", OffsetDateTime.now())
                .param("source", source)
                .param("principal", principal)
                .param("marketplace", marketplace)
                .param("event", event)
                .param("ref", ref)
                .param("sha", sha)
                .param("detail", detail)
                .param("tokenId", tokenId)
                .update();
    }

    public List<Map<String, Object>> list() {
        return jdbc.sql("SELECT * FROM fetch_log ORDER BY id").query().listOfRows();
    }

    /**
     * The ledger's entries of one actor kind (GW_0128). An indexable predicate on a typed column,
     * with no string parsing of {@code principal} and no join to {@code access_tokens} — which is
     * the whole reason the column is denormalised: a row must still say what it meant after the
     * credential it names has been revoked and its row deleted.
     */
    public List<Map<String, Object>> listByActorType(ActorType actorType) {
        return jdbc.sql("SELECT * FROM fetch_log WHERE actor_type = :actorType::fetch_log_actor_type ORDER BY id")
                .param("actorType", actorType.value())
                .query()
                .listOfRows();
    }

    /**
     * Every identity that has fetched one commit through the facade, with how often and when it
     * last did (GW_0053) — the blast-radius report of ARCHITECTURE.md §5, answered from the ledger
     * the gateway has been keeping all along rather than from anything new.
     *
     * <p>Only {@code upload-pack} entries count. A ref advertisement ({@code info-refs}) happens on
     * every {@code git fetch} whether or not anything is transferred, so counting it would name
     * teams that never received the content and bury the ones that did.
     *
     * <p>This names principals, not teams: the gateway knows who authenticated, and mapping a
     * principal to a team is the identity provider's knowledge, not the ledger's. Direct
     * per-team notification is a follow-on; what exists today is the list an operator acts on.
     */
    public List<Fetcher> fetchersOf(String sha) {
        return jdbc.sql("SELECT principal, COUNT(*) AS fetches, MAX(ts) AS last_fetch FROM fetch_log"
                        + " WHERE sha = :sha AND event = 'upload-pack' AND principal IS NOT NULL"
                        + " GROUP BY principal ORDER BY MAX(ts) DESC")
                .param("sha", sha)
                .query((rs, rowNum) -> new Fetcher(
                        rs.getString("principal"),
                        rs.getLong("fetches"),
                        MarketplaceRepository.instant(rs, "last_fetch")))
                .list();
    }

    /** One identity that received a snapshot's content through the facade. */
    @Schema(description = "An identity that fetched a snapshot's content through the git facade")
    public record Fetcher(
            @Schema(description = "Authenticated identity that fetched it")
            String principal,

            @Schema(description = "How many times it received a pack for this commit")
            long fetches,

            @Schema(description = "The most recent of those fetches")
            Instant lastFetch) {}

    /**
     * The adoption report's per-snapshot rows (GW_0075): content-transferring fetches since the
     * window start, grouped by (marketplace, sha). Only {@code upload-pack} entries count, for the
     * same reason {@link #fetchersOf(String)} gives: a ref advertisement is not a delivery.
     */
    public List<ShaAdoption> adoptionSince(Instant since) {
        return jdbc.sql("SELECT marketplace, sha, COUNT(*) AS fetches, COUNT(DISTINCT principal) AS identities,"
                        + " MAX(ts) AS last_fetch FROM fetch_log"
                        + " WHERE event = 'upload-pack' AND sha IS NOT NULL AND ts >= :since"
                        + " GROUP BY marketplace, sha ORDER BY marketplace, MAX(ts) DESC")
                .param("since", since.atOffset(ZoneOffset.UTC))
                .query((rs, rowNum) -> new ShaAdoption(
                        rs.getString("marketplace"),
                        rs.getString("sha"),
                        rs.getLong("fetches"),
                        rs.getLong("identities"),
                        MarketplaceRepository.instant(rs, "last_fetch")))
                .list();
    }

    /**
     * The same window aggregated per marketplace (GW_0075). Its own query rather than a sum over
     * {@link #adoptionSince(Instant)}: an identity that fetched two SHAs of one marketplace is one
     * identity, which no summation over per-SHA rows can know.
     */
    public List<MarketplaceFetches> marketplaceAdoptionSince(Instant since) {
        return jdbc.sql("SELECT marketplace, COUNT(*) AS fetches, COUNT(DISTINCT principal) AS identities,"
                        + " MAX(ts) AS last_fetch FROM fetch_log"
                        + " WHERE event = 'upload-pack' AND sha IS NOT NULL AND ts >= :since"
                        + " GROUP BY marketplace ORDER BY marketplace")
                .param("since", since.atOffset(ZoneOffset.UTC))
                .query((rs, rowNum) -> new MarketplaceFetches(
                        rs.getString("marketplace"),
                        rs.getLong("fetches"),
                        rs.getLong("identities"),
                        MarketplaceRepository.instant(rs, "last_fetch")))
                .list();
    }

    /**
     * Each identity's most recent content-transferring fetch per marketplace (GW_0076) — the row
     * staleness compares against the served tip. Window-free on purpose: staleness is a property
     * of an identity's latest state, not of a reporting period.
     */
    public List<LatestFetch> latestFetchPerIdentity() {
        return jdbc.sql("SELECT DISTINCT ON (principal, marketplace) principal, marketplace, sha, ts"
                        + " FROM fetch_log"
                        + " WHERE event = 'upload-pack' AND principal IS NOT NULL AND sha IS NOT NULL"
                        + " ORDER BY principal, marketplace, id DESC")
                .query((rs, rowNum) -> new LatestFetch(
                        rs.getString("principal"),
                        rs.getString("marketplace"),
                        rs.getString("sha"),
                        MarketplaceRepository.instant(rs, "ts")))
                .list();
    }

    /** One (marketplace, sha) adoption aggregate over the report window. */
    public record ShaAdoption(String marketplace, String sha, long fetches, long identities, Instant lastFetch) {}

    /** One marketplace's adoption aggregate over the report window. */
    public record MarketplaceFetches(String marketplace, long fetches, long identities, Instant lastFetch) {}

    /** One identity's most recent content-transferring fetch of one marketplace. */
    public record LatestFetch(String principal, String marketplace, String sha, Instant lastFetch) {}

    /**
     * The page of ledger entries an export consumer sees next: everything after its cursor, in
     * ledger order, bounded by {@code limit} (GW_0027, GW_0028).
     *
     * <p>{@code cutoff} excludes entries younger than the commit-settling lag. {@code id} comes
     * from a {@code BIGSERIAL}, which is assigned before commit, so without the cutoff a reader
     * could advance past a concurrently-inserted lower id and never see it again.
     */
    public List<AuditEntry> entriesAfter(long cursor, Instant cutoff, int limit) {
        return jdbc.sql("SELECT * FROM fetch_log WHERE id > :cursor AND ts <= :cutoff ORDER BY id LIMIT :limit")
                .param("cursor", cursor)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query(FetchLogRepository::map)
                .list();
    }

    /**
     * The sequence a reader would reach after consuming at most {@code limit} entries past
     * {@code cursor} — the resume cursor, known before a single entry has been streamed. Returns
     * {@code cursor} unchanged when nothing is available.
     */
    public long cursorAfter(long cursor, Instant cutoff, int limit) {
        return jdbc.sql("SELECT COALESCE(MAX(id), :cursor) FROM (SELECT id FROM fetch_log"
                        + " WHERE id > :cursor AND ts <= :cutoff ORDER BY id LIMIT :limit) page")
                .param("cursor", cursor)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query(Long.class)
                .single();
    }

    /** Highest ledger sequence written so far; 0 when the ledger is empty. */
    public long maxId() {
        return jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM fetch_log")
                .query(Long.class)
                .single();
    }

    /** One ledger entry as exported: the ledger sequence is the consumer's de-duplication key. */
    @Schema(description = "One append-only audit ledger entry")
    public record AuditEntry(
            @Schema(description = "Ledger sequence; the export cursor and de-duplication key")
            long id,

            @Schema(description = "When the entry was appended, ISO-8601")
            String ts,

            @Schema(description = "Client address for a facade fetch, or 'admin' for an administrative action")
            String source,

            @Schema(description = "Authenticated identity, when there was one")
            String principal,

            @Schema(description = "Marketplace the entry concerns, or '-'")
            String marketplace,

            @Schema(description = "What happened") String event,
            @Schema(description = "Served ref, for fetches") String ref,

            @Schema(description = "Commit SHA, when the entry concerns one")
            String sha,

            @Schema(description = "Free-text qualifier, such as the reason given for a vetting override")
            String detail,

            @Schema(description = "Id of the token that authenticated a facade entry, or null (GW_0067)")
            Long tokenId) {}

    private static AuditEntry map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEntry(
                rs.getLong("id"),
                // Rendered here rather than as a java.time value: the exported line is serialized by
                // a plain ObjectMapper, and ISO-8601 text is what a SIEM ingests anyway.
                String.valueOf(MarketplaceRepository.instant(rs, "ts")),
                rs.getString("source"),
                rs.getString("principal"),
                rs.getString("marketplace"),
                rs.getString("event"),
                rs.getString("ref"),
                rs.getString("sha"),
                rs.getString("detail"),
                tokenId(rs));
    }

    private static Long tokenId(ResultSet rs) throws SQLException {
        long value = rs.getLong("token_id");
        return rs.wasNull() ? null : value;
    }

    public record FetchRecord(long id, Instant ts, String source, String principal, String marketplace, String event) {}
}

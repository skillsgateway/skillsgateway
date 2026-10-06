package dev.skillsgateway.server.adoption;

import dev.skillsgateway.server.ingestion.SnapshotContentService.PluginContent;
import dev.skillsgateway.server.ingestion.SnapshotContentService.SkillInfo;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.storage.ServedTip;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Read-only adoption and staleness reporting (GW_OBSERVABILITY_0001, GW_OBSERVABILITY_0002): aggregation over the append-only
 * fetch ledger the gateway has been keeping all along, compared against the served tips the facade
 * itself answers from. Nothing here writes anything.
 *
 * <p>The presence report (GW_OBSERVABILITY_0006, GW_OBSERVABILITY_0007) adds the served commit
 * trees, read through {@link SnapshotContentResolver}; it is still nothing a client asserted
 * (GW_OBSERVABILITY_0008). Its constructor is that boundary: ClientTelemetryBoundaryTests pins it.
 */
@Service
@Requirements({"GW_OBSERVABILITY_0008"})
public class AdoptionService {

    /** The payload's own statement of what its numbers are (GW_OBSERVABILITY_0007). */
    static final String PRESENCE_STATEMENT = "Presence, not invocation: these counts say which identities received a"
            + " skill through the facade, never whether it was used. A git fetch transfers a whole snapshot, so"
            + " every skill in one snapshot has that snapshot's identity count.";

    private final FetchLogRepository fetchLogRepository;
    private final ServedTip servedTip;
    private final SnapshotContentResolver contentResolver;

    public AdoptionService(
            FetchLogRepository fetchLogRepository, ServedTip servedTip, SnapshotContentResolver contentResolver) {
        this.fetchLogRepository = fetchLogRepository;
        this.servedTip = servedTip;
        this.contentResolver = contentResolver;
    }

    /**
     * The adoption report: per marketplace, the window's content-transferring fetches, distinct
     * identities and most recent fetch, with the per-snapshot-SHA breakdown, each SHA marked
     * current against the served tip.
     *
     * <p>Two ledger aggregations rather than one folded in Java: the marketplace-level distinct
     * identity count cannot be summed from per-SHA rows (one identity, two SHAs, one identity).
     */
    @Requirements({"GW_OBSERVABILITY_0001"})
    public List<MarketplaceAdoption> adoption(int days) {
        Instant since = Instant.now().minus(Duration.ofDays(days));
        Map<String, List<FetchLogRepository.ShaAdoption>> bySha = new LinkedHashMap<>();
        for (FetchLogRepository.ShaAdoption row : fetchLogRepository.adoptionSince(since)) {
            bySha.computeIfAbsent(row.marketplace(), name -> new ArrayList<>()).add(row);
        }
        Map<String, Optional<String>> tips = new HashMap<>();
        List<MarketplaceAdoption> report = new ArrayList<>();
        for (FetchLogRepository.MarketplaceFetches totals : fetchLogRepository.marketplaceAdoptionSince(since)) {
            String servedSha = servedTip(tips, totals.marketplace()).orElse(null);
            List<SnapshotAdoption> snapshots = bySha.getOrDefault(totals.marketplace(), List.of()).stream()
                    .map(row -> new SnapshotAdoption(
                            row.sha(),
                            row.fetches(),
                            row.identities(),
                            row.lastFetch(),
                            row.sha().equals(servedSha)))
                    .toList();
            report.add(new MarketplaceAdoption(
                    totals.marketplace(),
                    servedSha,
                    totals.fetches(),
                    totals.identities(),
                    totals.lastFetch(),
                    snapshots));
        }
        return report;
    }

    /**
     * The staleness report: every identity whose most recent content-transferring fetch of a
     * marketplace is not that marketplace's currently served tip — including, with a null
     * {@code servedSha}, identities holding content of a marketplace that stopped serving
     * entirely, which is exactly what a revocation leaves behind.
     */
    @Requirements({"GW_OBSERVABILITY_0002"})
    public List<StaleIdentity> staleness() {
        Map<String, Optional<String>> tips = new HashMap<>();
        List<StaleIdentity> stale = new ArrayList<>();
        for (FetchLogRepository.LatestFetch latest : fetchLogRepository.latestFetchPerIdentity()) {
            Optional<String> tip = servedTip(tips, latest.marketplace());
            if (tip.isPresent() && tip.get().equals(latest.sha())) {
                continue;
            }
            stale.add(new StaleIdentity(
                    latest.principal(), latest.marketplace(), latest.sha(), latest.lastFetch(), tip.orElse(null)));
        }
        return stale;
    }

    /**
     * The presence report: for every skill in any commit the ledger records a content-transferring
     * fetch of, how many identities hold it and which snapshots delivered it, over what span.
     *
     * <p>An identity holds what its most recent fetch of a marketplace received, so each identity
     * counts against exactly one SHA per marketplace and per-SHA counts sum without double
     * counting. {@code since}, when given, drops identities whose latest fetch predates it; it
     * never narrows which deliveries are considered. The SHA set comes from the ledger alone, so
     * nothing the facade never served can be named here. A SHA whose content cannot be resolved
     * is listed as such with its measures, never dropped.
     */
    @Requirements({"GW_OBSERVABILITY_0006", "GW_OBSERVABILITY_0007"})
    public PresenceReport presence(Instant since) {
        Map<String, Long> holding = new HashMap<>();
        for (FetchLogRepository.LatestFetch latest : fetchLogRepository.latestFetchPerIdentity()) {
            if (since == null || !latest.lastFetch().isBefore(since)) {
                holding.merge(key(latest.marketplace(), latest.sha()), 1L, Long::sum);
            }
        }
        Map<String, Optional<String>> tips = new HashMap<>();
        Map<String, SkillAccumulator> skills = new LinkedHashMap<>();
        List<UnresolvedSnapshot> unresolved = new ArrayList<>();
        for (FetchLogRepository.ShaDelivery delivery : fetchLogRepository.deliveriesPerSha()) {
            String marketplace = delivery.marketplace();
            long holders = holding.getOrDefault(key(marketplace, delivery.sha()), 0L);
            boolean current = delivery.sha().equals(servedTip(tips, marketplace).orElse(null));
            Optional<List<PluginContent>> content = contentResolver.resolve(marketplace, delivery.sha());
            if (content.isEmpty()) {
                unresolved.add(new UnresolvedSnapshot(
                        marketplace, delivery.sha(), holders, delivery.firstFetch(), delivery.lastFetch(), current));
                continue;
            }
            for (PluginContent plugin : content.get()) {
                for (SkillInfo skill : plugin.skills()) {
                    skills.computeIfAbsent(
                                    marketplace + '\0' + plugin.name() + '\0' + skill.name(),
                                    ignored -> new SkillAccumulator(marketplace, plugin.name(), skill.name()))
                            .add(skill.path(), delivery, holders, current);
                }
            }
        }
        List<SkillPresence> rows = skills.values().stream()
                .map(SkillAccumulator::toPresence)
                .sorted(Comparator.comparing(SkillPresence::marketplace)
                        .thenComparing(SkillPresence::plugin, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(SkillPresence::skill))
                .toList();
        return new PresenceReport("presence", PRESENCE_STATEMENT, since, rows, List.copyOf(unresolved));
    }

    private static String key(String marketplace, String sha) {
        return marketplace + '\0' + sha;
    }

    /** One skill's rows folded across the SHAs that delivered it. */
    private static final class SkillAccumulator {

        private final String marketplace;
        private final String plugin;
        private final String skill;
        private final List<SnapshotHolding> snapshots = new ArrayList<>();
        private String path;
        private Instant lastPathFetch;
        private long holders;
        private Instant firstDelivered;
        private Instant lastDelivered;

        SkillAccumulator(String marketplace, String plugin, String skill) {
            this.marketplace = marketplace;
            this.plugin = plugin;
            this.skill = skill;
        }

        void add(String skillPath, FetchLogRepository.ShaDelivery delivery, long shaHolders, boolean current) {
            snapshots.add(new SnapshotHolding(delivery.sha(), shaHolders, delivery.lastFetch(), current));
            holders += shaHolders;
            if (firstDelivered == null || delivery.firstFetch().isBefore(firstDelivered)) {
                firstDelivered = delivery.firstFetch();
            }
            if (lastDelivered == null || delivery.lastFetch().isAfter(lastDelivered)) {
                lastDelivered = delivery.lastFetch();
            }
            // The path of the most recently delivered SHA, since a skill can move between snapshots.
            if (lastPathFetch == null || delivery.lastFetch().isAfter(lastPathFetch)) {
                lastPathFetch = delivery.lastFetch();
                path = skillPath;
            }
        }

        SkillPresence toPresence() {
            List<SnapshotHolding> ordered = snapshots.stream()
                    .sorted(Comparator.comparing(SnapshotHolding::lastFetch).reversed())
                    .toList();
            return new SkillPresence(
                    marketplace, plugin, skill, path, holders, ordered.size(), firstDelivered, lastDelivered, ordered);
        }
    }

    /** The served tip, memoised for one report pass: a marketplace appears on many rows. */
    private Optional<String> servedTip(Map<String, Optional<String>> cache, String marketplace) {
        return cache.computeIfAbsent(marketplace, servedTip::of);
    }

    /** One snapshot SHA's share of a marketplace's adoption over the report window. */
    @Schema(description = "Adoption of one snapshot SHA over the report window")
    public record SnapshotAdoption(
            @Schema(description = "Upstream commit SHA that was fetched")
            String sha,

            @Schema(description = "Content-transferring fetches of this SHA in the window")
            long fetches,

            @Schema(description = "Distinct identities that fetched this SHA in the window")
            long identities,

            @Schema(description = "Most recent of those fetches")
            Instant lastFetch,

            @Schema(description = "Whether this SHA is the currently served tip")
            boolean current) {}

    /** One marketplace's adoption over the report window. */
    @Schema(description = "Adoption of one marketplace over the report window")
    public record MarketplaceAdoption(
            @Schema(description = "Marketplace name as the ledger records it")
            String marketplace,

            @Schema(description = "Currently served tip, or null when the marketplace is not serving")
            String servedSha,

            @Schema(description = "Content-transferring fetches in the window")
            long fetches,

            @Schema(description = "Distinct identities that fetched in the window")
            long identities,

            @Schema(description = "Most recent fetch in the window")
            Instant lastFetch,

            @Schema(description = "Per-snapshot-SHA breakdown, most recently fetched first")
            List<SnapshotAdoption> snapshots) {}

    /** One identity whose latest received content is not what the marketplace serves now. */
    @Schema(description = "An identity whose most recent fetch is not the currently served tip")
    public record StaleIdentity(
            @Schema(description = "Authenticated identity that fetched")
            String principal,

            @Schema(description = "Marketplace the fetch was of")
            String marketplace,

            @Schema(description = "SHA the identity last received")
            String sha,

            @Schema(description = "When it last received it")
            Instant lastFetch,

            @Schema(
                    description = "Currently served tip it diverges from, or null when the marketplace"
                            + " is no longer serving")
            String servedSha) {}

    /**
     * The presence report (GW_OBSERVABILITY_0006, GW_OBSERVABILITY_0007). {@code measure} and
     * {@code statement} are part of the payload so the numbers cannot travel without saying what
     * they are.
     */
    @Schema(description = "Which identities hold which served skills: presence, not invocation")
    public record PresenceReport(
            @Schema(
                    description = "What the counts measure; always \"presence\"",
                    allowableValues = {"presence"})
            String measure,

            @Schema(description = "What the counts mean and do not mean, stated in the payload itself")
            String statement,

            @Schema(description = "Identities whose latest fetch predates this were left out; null for all time")
            Instant since,

            @Schema(description = "One entry per (marketplace, plugin, skill) any delivered snapshot contains")
            List<SkillPresence> skills,

            @Schema(
                    description = "Delivered snapshots whose content can no longer be resolved; their holders may"
                            + " hold any skill")
            List<UnresolvedSnapshot> unresolved) {}

    /** One skill's presence across every snapshot that delivered it. */
    @Schema(description = "One skill's presence across every delivered snapshot that contains it")
    public record SkillPresence(
            @Schema(description = "Marketplace name as served")
            String marketplace,

            @Schema(description = "Plugin name from the manifest, as served")
            String plugin,

            @Schema(description = "Skill directory name, as served")
            String skill,

            @Schema(description = "Path of the SKILL.md in the most recently delivered snapshot")
            String path,

            @Schema(
                    description = "Identities whose latest fetch of the marketplace received a snapshot containing"
                            + " this skill. Uniform across a snapshot's skills by construction")
            long identitiesHolding,

            @Schema(description = "Distinct snapshots containing this skill that the facade delivered")
            int snapshotsDelivering,

            @Schema(description = "First fetch of any of those snapshots")
            Instant firstDelivered,

            @Schema(description = "Most recent fetch of any of those snapshots")
            Instant lastDelivered,

            @Schema(description = "Those snapshots, most recently fetched first")
            List<SnapshotHolding> snapshots) {}

    /** One delivered snapshot's holders. */
    @Schema(description = "One delivered snapshot and the identities whose latest fetch it is")
    public record SnapshotHolding(
            @Schema(description = "Commit SHA the facade delivered")
            String sha,

            @Schema(description = "Identities whose latest fetch of the marketplace is this SHA")
            long identitiesHolding,

            @Schema(description = "Most recent fetch of this SHA")
            Instant lastFetch,

            @Schema(description = "Whether this SHA is the currently served tip")
            boolean current) {}

    /** A delivered snapshot whose tree retention reclaimed, or whose manifest no longer parses. */
    @Schema(description = "A delivered snapshot whose content is not resolvable; reported, never dropped")
    public record UnresolvedSnapshot(
            @Schema(description = "Marketplace name as the ledger records it")
            String marketplace,

            @Schema(description = "Commit SHA the facade delivered")
            String sha,

            @Schema(description = "Identities whose latest fetch of the marketplace is this SHA")
            long identitiesHolding,

            @Schema(description = "First fetch of this SHA") Instant firstDelivered,

            @Schema(description = "Most recent fetch of this SHA")
            Instant lastDelivered,

            @Schema(description = "Whether this SHA is the currently served tip")
            boolean current) {}
}

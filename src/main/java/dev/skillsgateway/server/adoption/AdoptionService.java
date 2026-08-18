package dev.skillsgateway.server.adoption;

import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Service;

/**
 * Read-only adoption and staleness reporting (GW_0075, GW_0076): aggregation over the append-only
 * fetch ledger the gateway has been keeping all along, compared against the served tips the facade
 * itself answers from. Nothing here writes anything.
 */
@Service
public class AdoptionService {

    private static final String SERVED_REF = "refs/heads/main";

    private final FetchLogRepository fetchLogRepository;
    private final GitStorage storage;

    public AdoptionService(FetchLogRepository fetchLogRepository, GitStorage storage) {
        this.fetchLogRepository = fetchLogRepository;
        this.storage = storage;
    }

    /**
     * The adoption report: per marketplace, the window's content-transferring fetches, distinct
     * identities and most recent fetch, with the per-snapshot-SHA breakdown, each SHA marked
     * current against the served tip.
     *
     * <p>Two ledger aggregations rather than one folded in Java: the marketplace-level distinct
     * identity count cannot be summed from per-SHA rows (one identity, two SHAs, one identity).
     */
    @Requirements({"GW_0075"})
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
    @Requirements({"GW_0076"})
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
     * The served tip of a marketplace, resolved from the published repository — the same read the
     * facade serves from, so the report can never disagree with what a {@code git fetch} returns.
     * Empty when the marketplace is not serving (never published, revoked, or unpublished).
     */
    private Optional<String> servedTip(Map<String, Optional<String>> cache, String marketplace) {
        return cache.computeIfAbsent(marketplace, name -> {
            try {
                Optional<Repository> serving = storage.publishedIfServing(name);
                if (serving.isEmpty()) {
                    return Optional.empty();
                }
                try (Repository repository = serving.get()) {
                    ObjectId main = repository.resolve(SERVED_REF);
                    return Optional.ofNullable(main).map(ObjectId::name);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
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
}

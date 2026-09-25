package dev.skillsgateway.server.admin;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.ingestion.ForgeMetadataService;
import dev.skillsgateway.server.ingestion.UpstreamCredentials;
import dev.skillsgateway.server.ingestion.UpstreamException;
import dev.skillsgateway.server.ingestion.UpstreamGit;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.storage.RefTransitions;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one registration path (GW_INGEST_0005, GW_FACADE_0005): every marketplace — registered interactively
 * through the API or declared in the estate configuration (GW_ESTATE_0002) — enters through this gate.
 * The validations live here precisely so no second caller can grow a second, drifting copy of the
 * trust boundary.
 */
@Service
public class MarketplaceRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceRegistrationService.class);

    /** Ledger event for a marketplace registered although its upstream could not be read (GW_INGEST_0041). */
    public static final String EVENT_UPSTREAM_UNREACHABLE = "marketplace-upstream-unreachable";

    /**
     * What an unreadable upstream does to a registration (GW_INGEST_0040, GW_INGEST_0041): an
     * administrator at the API is refused and can correct the URL; a declaration converged at
     * startup is registered and reported, so the estate does not depend on the network's weather.
     */
    public enum Reachability {
        REFUSE,
        REPORT
    }

    public static final Pattern MARKETPLACE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");

    private static final Set<String> ORIGINS = Set.of(Marketplace.ORIGIN_UPSTREAM, Marketplace.ORIGIN_HOSTED);

    private static final Set<String> PUSH_POLICIES =
            Set.of(Marketplace.PUSH_APPEND_ONLY, Marketplace.PUSH_ALLOW_REWRITE);

    private final MarketplaceRepository marketplaceRepository;
    private final SkillsGatewayProperties properties;
    private final ForgeMetadataService forgeMetadataService;
    private final AdminAuditLogger auditLogger;
    private final GitStorage storage;
    private final WebhookService webhookService;
    private final SnapshotRepository snapshotRepository;
    private final UpstreamGit upstreamGit;
    private final UpstreamCredentials upstreamCredentials;

    public MarketplaceRegistrationService(
            MarketplaceRepository marketplaceRepository,
            SkillsGatewayProperties properties,
            ForgeMetadataService forgeMetadataService,
            AdminAuditLogger auditLogger,
            GitStorage storage,
            WebhookService webhookService,
            SnapshotRepository snapshotRepository,
            UpstreamGit upstreamGit,
            UpstreamCredentials upstreamCredentials) {
        this.marketplaceRepository = marketplaceRepository;
        this.properties = properties;
        this.forgeMetadataService = forgeMetadataService;
        this.auditLogger = auditLogger;
        this.storage = storage;
        this.webhookService = webhookService;
        this.snapshotRepository = snapshotRepository;
        this.upstreamGit = upstreamGit;
        this.upstreamCredentials = upstreamCredentials;
    }

    /** A successful registration, plus any non-blocking warnings about it (GW_INGEST_0029). */
    public record RegistrationOutcome(Marketplace marketplace, List<String> warnings) {}

    /**
     * Validates, registers, and appends the ledger entry with the acting identity. Statuses match
     * the API contract; a non-HTTP caller (the estate reconciler) reports the reason instead.
     */
    @Requirements({"GW_INGEST_0001"})
    public RegistrationOutcome register(String name, String url, String actor) {
        return register(name, url, Marketplace.ORIGIN_UPSTREAM, null, actor);
    }

    /**
     * Validates, registers, and appends the ledger entry with the acting identity. Statuses match
     * the API contract; a non-HTTP caller (the estate reconciler) reports the reason instead.
     *
     * <p>A hosted marketplace (GW_FACADE_0006) has no upstream, so the scheme allowlist has nothing to
     * check and a supplied URL is a contradiction rather than an unused field; its origin
     * repository is created here so a publisher can push the moment registration returns.
     */
    @Requirements({"GW_INGEST_0001", "GW_INGEST_0040"})
    public RegistrationOutcome register(String name, String url, String origin, String pushPolicy, String actor) {
        return register(name, url, origin, pushPolicy, actor, Reachability.REFUSE);
    }

    /**
     * As above, deciding what an unreadable upstream does. The upstream is read last, after every
     * validation that needs no network, so a request refused on those never contacts it.
     */
    @Requirements({
        "GW_INGEST_0001",
        "GW_APPROVAL_0010",
        "GW_FACADE_0006",
        "GW_WEBHOOK_0009",
        "GW_INGEST_0040",
        "GW_INGEST_0041",
        "GW_INGEST_0054",
        "GW_INGEST_0055"
    })
    public RegistrationOutcome register(
            String name, String url, String origin, String pushPolicy, String actor, Reachability reachability) {
        if (name == null || !MARKETPLACE_NAME.matcher(name).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "name must match " + MARKETPLACE_NAME.pattern());
        }
        requireNotReservedName(name);
        String resolvedOrigin = origin == null || origin.isBlank() ? Marketplace.ORIGIN_UPSTREAM : origin;
        if (!ORIGINS.contains(resolvedOrigin)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "origin must be one of %s".formatted(ORIGINS));
        }
        boolean hosted = Marketplace.ORIGIN_HOSTED.equals(resolvedOrigin);
        String resolvedPolicy = requirePushPolicy(pushPolicy, hosted);
        List<String> warnings = List.of();
        if (hosted) {
            if (url != null && !url.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "a hosted marketplace has no upstream url; it is pushed to");
            }
        } else {
            requireAllowlistedScheme(url);
            requireNoUserinfo(url);
            warnings = duplicateUrlWarnings(url);
        }
        if (marketplaceRepository.findByName(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "marketplace '%s' already exists".formatted(name));
        }
        UpstreamException unreadable = hosted ? null : readUpstream(name, url, reachability);
        startFromNothing(name, hosted);
        Marketplace marketplace = marketplaceRepository.register(
                name,
                url,
                hosted ? null : forgeMetadataService.resolve(url).orElse(null),
                resolvedOrigin,
                resolvedPolicy,
                // The registrant is a column, not only a ledger row (GW_APPROVAL_0010): the four-eyes rule
                // reads it when this marketplace's snapshots are approved.
                actor);
        if (hosted) {
            createOriginRepository(marketplace.name());
        }
        auditLogger.record(
                actor, marketplace.name(), "marketplace-registered", null, registrationDetail(resolvedOrigin, url));
        if (unreadable != null) {
            auditLogger.record(
                    actor,
                    marketplace,
                    EVENT_UPSTREAM_UNREACHABLE,
                    null,
                    unreadable.failure().describe());
            warnings = Stream.concat(
                            warnings.stream(),
                            Stream.of("upstream unreachable: "
                                    + unreadable.failure().describe()))
                    .toList();
        }
        webhookService.emitMarketplace(
                WebhookEvent.MARKETPLACE_REGISTERED, marketplace.name(), actor, "origin=" + resolvedOrigin);
        return new RegistrationOutcome(marketplace, warnings);
    }

    /**
     * Lists the upstream over the path ingestion fetches with and resolves the ref the gateway pins
     * (GW_INGEST_0040). Refused, the exception reaches the caller with its translated cause and nothing
     * has been written; reported, it is returned for the caller to record (GW_INGEST_0041).
     */
    @Requirements({"GW_INGEST_0040", "GW_INGEST_0041"})
    private UpstreamException readUpstream(String name, String url, Reachability reachability) {
        try {
            upstreamGit.probe(url);
            return null;
        } catch (UpstreamException e) {
            log.warn("upstream of marketplace '{}' could not be read at registration: {}", name, e.getMessage());
            if (reachability == Reachability.REFUSE) {
                throw e;
            }
            return e;
        }
    }

    /**
     * A name that belonged to a removed marketplace comes back empty (GW_INGEST_0035). Storage is keyed
     * by name, so before the new row exists: every predecessor snapshot's served references are removed
     * — removal already did this through revocation, and this is what keeps a reference a failed
     * unpublish left behind from being served under the successor — and a hosted successor's origin
     * loses its predecessor's lineage, so it neither ingests the old pushes nor refuses its own first
     * push as a rewrite. Quarantine is left alone: its pins are the predecessor's evidence.
     */
    @Requirements({"GW_INGEST_0035"})
    private void startFromNothing(String name, boolean hosted) {
        try {
            for (Marketplace predecessor : marketplaceRepository.retiredByName(name)) {
                for (Snapshot snapshot : snapshotRepository.listByMarketplace(predecessor.id())) {
                    storage.unpublish(name, snapshot.sha());
                }
            }
            if (hosted) {
                Optional<Repository> origin = storage.hostedIfPresent(name);
                if (origin.isPresent()) {
                    try (Repository repository = origin.get()) {
                        RefTransitions.delete(repository, Marketplace.LINEAGE_REF);
                    }
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "could not clear what the removed marketplace '%s' left in storage".formatted(name),
                    e);
        }
    }

    /**
     * Non-blocking: tracking one upstream under two marketplace names is a legitimate way to test
     * a marketplace before promoting it, so a collision here is surfaced, never refused. Compared
     * by normalized URL (GW_INGEST_0029) against every other upstream marketplace, so this also catches a
     * duplicate that only case, a trailing slash or a {@code .git} suffix disguises.
     */
    @Requirements({"GW_INGEST_0029"})
    private List<String> duplicateUrlWarnings(String url) {
        String normalized = CloneUrlNormalizer.normalize(url);
        if (normalized == null) {
            return List.of();
        }
        return marketplaceRepository.list().stream()
                .filter(m -> m.url() != null)
                .filter(m -> normalized.equals(CloneUrlNormalizer.normalize(m.url())))
                .map(m -> "url already registered as " + m.name())
                .toList();
    }

    /** A push policy is a hosted marketplace's decision; an upstream one has no lineage to rewrite. */
    @Requirements({"GW_FACADE_0006"})
    private String requirePushPolicy(String pushPolicy, boolean hosted) {
        if (pushPolicy == null || pushPolicy.isBlank()) {
            return Marketplace.PUSH_APPEND_ONLY;
        }
        if (!hosted) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "a push policy applies only to a hosted marketplace");
        }
        if (!PUSH_POLICIES.contains(pushPolicy)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "pushPolicy must be one of %s".formatted(PUSH_POLICIES));
        }
        return pushPolicy;
    }

    /**
     * Created at registration rather than on first push, so the publish endpoint never has to
     * decide whether an unknown marketplace means "not registered" or "not pushed to yet" — it
     * answers not-found for both, and the answer is the same either way.
     */
    @Requirements({"GW_FACADE_0006"})
    private void createOriginRepository(String name) {
        try (Repository ignored = storage.hosted(name)) {
            // Opening creates it; nothing else to do.
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "could not create the origin repository", e);
        }
    }

    /**
     * The ledger names the credential prefix an upstream was read with, never its token
     * (GW_INGEST_0055): the token's scope is the limit on what a registration can pull in.
     */
    @Requirements({"GW_INGEST_0055"})
    private String registrationDetail(String origin, String url) {
        String detail = "origin=" + origin;
        if (Marketplace.ORIGIN_HOSTED.equals(origin)) {
            return detail;
        }
        return upstreamCredentials
                .select(url)
                .map(selected -> detail + " credential=" + selected.urlPrefix())
                .orElse(detail);
    }

    /**
     * A credential in the URL would be stored in the marketplace record and repeated by every
     * response that shows it (GW_INGEST_0054); upstream credentials are configuration. The
     * authority is also read as written, so an opaque parse cannot hide an {@code @}.
     */
    @Requirements({"GW_INGEST_0054"})
    private static void requireNoUserinfo(String url) {
        boolean userinfo;
        try {
            userinfo = new URI(url).getRawUserInfo() != null;
        } catch (URISyntaxException e) {
            userinfo = false;
        }
        int authority = url.indexOf("://");
        if (authority >= 0) {
            int slash = url.indexOf('/', authority + 3);
            userinfo |= url.substring(authority + 3, slash < 0 ? url.length() : slash)
                            .indexOf('@')
                    >= 0;
        }
        if (userinfo) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "url must not embed a credential; configure one under"
                            + " skills-gateway.ingestion.upstream-credentials instead");
        }
    }

    /** Fails closed: scheme-less and unparseable URLs are rejected along with non-allowlisted schemes. */
    @Requirements({"GW_INGEST_0005"})
    private void requireAllowlistedScheme(String url) {
        String scheme = null;
        if (url != null) {
            try {
                scheme = new URI(url).getScheme();
            } catch (URISyntaxException e) {
                scheme = null;
            }
        }
        if (scheme == null || !properties.allowedUrlSchemes().contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "url scheme must be one of %s".formatted(properties.allowedUrlSchemes()));
        }
    }

    /** The virtual catalog occupies its facade path; a marketplace there would collide (GW_FACADE_0005). */
    @Requirements({"GW_FACADE_0005"})
    private void requireNotReservedName(String name) {
        if (name.equals(properties.catalog().name())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "'%s' is reserved for the virtual catalog".formatted(name));
        }
    }
}

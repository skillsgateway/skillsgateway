package dev.skillsgateway.server.admin;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.ingestion.ForgeMetadataService;
import dev.skillsgateway.server.ingestion.UpstreamCredentials;
import dev.skillsgateway.server.ingestion.UpstreamException;
import dev.skillsgateway.server.ingestion.UpstreamGit;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceName;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
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

    /** Ledger event for an upstream marketplace's URL corrected before its first snapshot (GW_INGEST_0066). */
    public static final String EVENT_URL_CHANGED = "marketplace-url-changed";

    /**
     * What a registration's ledger entry and lifecycle event say first. The key survives from when a
     * marketplace could be hosted: receivers and the ledger's readers matched on it, and the lifecycle
     * event's detail is a required field.
     */
    private static final String REGISTERED_DETAIL = "origin=upstream";

    /**
     * What an unreadable upstream does to a registration (GW_INGEST_0040, GW_INGEST_0041): an
     * administrator at the API is refused and can correct the URL; a declaration converged at
     * startup is registered and reported, so the estate does not depend on the network's weather.
     */
    public enum Reachability {
        REFUSE,
        REPORT
    }

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
    @Requirements({"GW_INGEST_0001", "GW_INGEST_0040"})
    public RegistrationOutcome register(String name, String url, String actor) {
        return register(name, url, actor, Reachability.REFUSE);
    }

    /**
     * As above, deciding what an unreadable upstream does. The upstream is read last, after every
     * validation that needs no network, so a request refused on those never contacts it.
     */
    @Requirements({
        "GW_INGEST_0001",
        "GW_APPROVAL_0010",
        "GW_WEBHOOK_0009",
        "GW_INGEST_0040",
        "GW_INGEST_0041",
        "GW_INGEST_0054",
        "GW_INGEST_0055",
        "GW_INGEST_0063",
        "GW_INGEST_0064"
    })
    public RegistrationOutcome register(String name, String url, String actor, Reachability reachability) {
        if (!MarketplaceName.isValid(name)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, MarketplaceName.RULE);
        }
        requireNotReservedName(name);
        requireAllowlistedScheme(url);
        requireNoUserinfo(url);
        List<String> warnings = duplicateUrlWarnings(url, name);
        if (marketplaceRepository.findByName(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "marketplace '%s' already exists".formatted(name));
        }
        UpstreamException unreadable = readUpstream(name, url, reachability);
        startFromNothing(name);
        Marketplace marketplace = marketplaceRepository.register(
                name,
                url,
                forgeMetadataService.resolve(url).orElse(null),
                // The registrant is a column, not only a ledger row (GW_APPROVAL_0010): the four-eyes rule
                // reads it when this marketplace's snapshots are approved.
                actor);
        auditLogger.record(actor, marketplace.name(), "marketplace-registered", null, registrationDetail(url));
        warnings = withUnreachable(actor, marketplace, warnings, unreadable);
        // The detail keeps its one value: the field is required on every delivery, and a receiver that
        // matched on it should not find it changed. Every marketplace now comes from an upstream.
        webhookService.emitMarketplace(
                WebhookEvent.MARKETPLACE_REGISTERED, marketplace.name(), actor, REGISTERED_DETAIL);
        return new RegistrationOutcome(marketplace, warnings);
    }

    /**
     * Corrects an upstream marketplace's URL until its first snapshot (GW_INGEST_0066): the new URL faces
     * registration's checks in registration's order, and the editor becomes the registrant because
     * the editor chose the upstream now in effect. An unchanged URL is answered as it stands, unrecorded.
     */
    @Requirements({
        "GW_INGEST_0066",
        "GW_INGEST_0005",
        "GW_INGEST_0029",
        "GW_INGEST_0040",
        "GW_INGEST_0041",
        "GW_INGEST_0054"
    })
    public RegistrationOutcome changeUrl(String name, String url, String actor, Reachability reachability) {
        Marketplace current = marketplaceRepository.findByName(name).orElseThrow(() -> notFound(name));
        if (marketplaceRepository.hasSnapshot(current.id())) {
            throw snapshotExists(name);
        }
        requireAllowlistedScheme(url);
        requireNoUserinfo(url);
        if (url.equals(current.url())) {
            return new RegistrationOutcome(current, List.of());
        }
        List<String> warnings = duplicateUrlWarnings(url, name);
        UpstreamException unreadable = readUpstream(name, url, reachability);
        MarketplaceRepository.UrlChange change = marketplaceRepository.changeUrlBeforeFirstSnapshot(
                current.id(), url, forgeMetadataService.resolve(url).orElse(null), actor);
        if (change == MarketplaceRepository.UrlChange.NOT_LIVE) {
            throw notFound(name);
        }
        if (change == MarketplaceRepository.UrlChange.HAS_SNAPSHOT) {
            throw snapshotExists(name);
        }
        Marketplace changed = marketplaceRepository.findById(current.id()).orElseThrow();
        auditLogger.record(
                actor,
                changed,
                EVENT_URL_CHANGED,
                null,
                "from=%s to=%s%s".formatted(current.url(), url, credentialDetail(url)));
        return new RegistrationOutcome(changed, withUnreachable(actor, changed, warnings, unreadable));
    }

    private static ResponseStatusException notFound(String name) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(name));
    }

    private static ResponseStatusException snapshotExists(String name) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                ("marketplace '%s' has a snapshot, so its url is the source of record and cannot change;"
                                + " remove the marketplace and register it again")
                        .formatted(name));
    }

    /** An upstream read under {@link Reachability#REPORT} that failed: on the ledger and in the warnings. */
    @Requirements({"GW_INGEST_0041"})
    private List<String> withUnreachable(
            String actor, Marketplace marketplace, List<String> warnings, UpstreamException unreadable) {
        if (unreadable == null) {
            return warnings;
        }
        auditLogger.record(
                actor,
                marketplace,
                EVENT_UPSTREAM_UNREACHABLE,
                null,
                unreadable.failure().describe());
        return Stream.concat(
                        warnings.stream(),
                        Stream.of(
                                "upstream unreachable: " + unreadable.failure().describe()))
                .toList();
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
     * unpublish left behind from being served under the successor. Quarantine is left alone: its pins are the predecessor's evidence.
     */
    @Requirements({"GW_INGEST_0035"})
    private void startFromNothing(String name) {
        try {
            for (Marketplace predecessor : marketplaceRepository.retiredByName(name)) {
                for (Snapshot snapshot : snapshotRepository.listByMarketplace(predecessor.id())) {
                    storage.unpublish(name, snapshot.sha());
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
    private List<String> duplicateUrlWarnings(String url, String self) {
        String normalized = CloneUrlNormalizer.normalize(url);
        if (normalized == null) {
            return List.of();
        }
        return marketplaceRepository.list().stream()
                .filter(m -> m.url() != null && !m.name().equals(self))
                .filter(m -> normalized.equals(CloneUrlNormalizer.normalize(m.url())))
                .map(m -> "url already registered as " + m.name())
                .toList();
    }

    /**
     * The ledger names the credential prefix an upstream was read with, and whether it is a GitHub App,
     * never its token (GW_INGEST_0055, GW_INGEST_0062): the token's scope is the limit on what a
     * registration can pull in.
     */
    @Requirements({"GW_INGEST_0055", "GW_INGEST_0062"})
    private String registrationDetail(String url) {
        return REGISTERED_DETAIL + credentialDetail(url);
    }

    @Requirements({"GW_INGEST_0055", "GW_INGEST_0062"})
    private String credentialDetail(String url) {
        return upstreamCredentials
                .select(url)
                .map(selected ->
                        " credential=" + selected.urlPrefix() + (selected.isGitHubApp() ? " (github-app)" : ""))
                .orElse("");
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

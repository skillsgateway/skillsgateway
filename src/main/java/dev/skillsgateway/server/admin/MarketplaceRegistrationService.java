package dev.skillsgateway.server.admin;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.ingestion.ForgeMetadataService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one registration path (GW_0016, GW_0063): every marketplace — registered interactively
 * through the API or declared in the estate configuration (GW_0084) — enters through this gate.
 * The validations live here precisely so no second caller can grow a second, drifting copy of the
 * trust boundary.
 */
@Service
public class MarketplaceRegistrationService {

    public static final Pattern MARKETPLACE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");

    private static final Set<String> ORIGINS = Set.of(Marketplace.ORIGIN_UPSTREAM, Marketplace.ORIGIN_HOSTED);

    private static final Set<String> PUSH_POLICIES =
            Set.of(Marketplace.PUSH_APPEND_ONLY, Marketplace.PUSH_ALLOW_REWRITE);

    private final MarketplaceRepository marketplaceRepository;
    private final SkillsGatewayProperties properties;
    private final ForgeMetadataService forgeMetadataService;
    private final AdminAuditLogger auditLogger;
    private final GitStorage storage;

    public MarketplaceRegistrationService(
            MarketplaceRepository marketplaceRepository,
            SkillsGatewayProperties properties,
            ForgeMetadataService forgeMetadataService,
            AdminAuditLogger auditLogger,
            GitStorage storage) {
        this.marketplaceRepository = marketplaceRepository;
        this.properties = properties;
        this.forgeMetadataService = forgeMetadataService;
        this.auditLogger = auditLogger;
        this.storage = storage;
    }

    /**
     * Validates, registers, and appends the ledger entry with the acting identity. Statuses match
     * the API contract; a non-HTTP caller (the estate reconciler) reports the reason instead.
     */
    @Requirements({"GW_0001"})
    public Marketplace register(String name, String url, String actor) {
        return register(name, url, Marketplace.ORIGIN_UPSTREAM, null, actor);
    }

    /**
     * Validates, registers, and appends the ledger entry with the acting identity. Statuses match
     * the API contract; a non-HTTP caller (the estate reconciler) reports the reason instead.
     *
     * <p>A hosted marketplace (GW_0101) has no upstream, so the scheme allowlist has nothing to
     * check and a supplied URL is a contradiction rather than an unused field; its origin
     * repository is created here so a publisher can push the moment registration returns.
     */
    @Requirements({"GW_0001", "GW_0096", "GW_0101"})
    public Marketplace register(String name, String url, String origin, String pushPolicy, String actor) {
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
        if (hosted) {
            if (url != null && !url.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "a hosted marketplace has no upstream url; it is pushed to");
            }
        } else {
            requireAllowlistedScheme(url);
        }
        if (marketplaceRepository.findByName(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "marketplace '%s' already exists".formatted(name));
        }
        Marketplace marketplace = marketplaceRepository.register(
                name,
                url,
                hosted ? null : forgeMetadataService.resolve(url).orElse(null),
                resolvedOrigin,
                resolvedPolicy,
                // The registrant is a column, not only a ledger row (GW_0096): the four-eyes rule
                // reads it when this marketplace's snapshots are approved.
                actor);
        if (hosted) {
            createOriginRepository(marketplace.name());
        }
        auditLogger.record(actor, marketplace.name(), "marketplace-registered", null, "origin=" + resolvedOrigin);
        return marketplace;
    }

    /** A push policy is a hosted marketplace's decision; an upstream one has no lineage to rewrite. */
    @Requirements({"GW_0101"})
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
    @Requirements({"GW_0101"})
    private void createOriginRepository(String name) {
        try (Repository ignored = storage.hosted(name)) {
            // Opening creates it; nothing else to do.
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "could not create the origin repository", e);
        }
    }

    /** Fails closed: scheme-less and unparseable URLs are rejected along with non-allowlisted schemes. */
    @Requirements({"GW_0016"})
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

    /** The virtual catalog occupies its facade path; a marketplace there would collide (GW_0063). */
    @Requirements({"GW_0063"})
    private void requireNotReservedName(String name) {
        if (name.equals(properties.catalog().name())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "'%s' is reserved for the virtual catalog".formatted(name));
        }
    }
}

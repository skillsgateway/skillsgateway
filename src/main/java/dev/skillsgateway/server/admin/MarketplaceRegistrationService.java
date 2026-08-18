package dev.skillsgateway.server.admin;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.ingestion.ForgeMetadataService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import io.github.reqstool.annotations.Requirements;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;
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

    private final MarketplaceRepository marketplaceRepository;
    private final SkillsGatewayProperties properties;
    private final ForgeMetadataService forgeMetadataService;
    private final AdminAuditLogger auditLogger;

    public MarketplaceRegistrationService(
            MarketplaceRepository marketplaceRepository,
            SkillsGatewayProperties properties,
            ForgeMetadataService forgeMetadataService,
            AdminAuditLogger auditLogger) {
        this.marketplaceRepository = marketplaceRepository;
        this.properties = properties;
        this.forgeMetadataService = forgeMetadataService;
        this.auditLogger = auditLogger;
    }

    /**
     * Validates, registers, and appends the ledger entry with the acting identity. Statuses match
     * the API contract; a non-HTTP caller (the estate reconciler) reports the reason instead.
     */
    @Requirements({"GW_0001"})
    public Marketplace register(String name, String url, String actor) {
        if (name == null || !MARKETPLACE_NAME.matcher(name).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "name must match " + MARKETPLACE_NAME.pattern());
        }
        requireNotReservedName(name);
        requireAllowlistedScheme(url);
        if (marketplaceRepository.findByName(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "marketplace '%s' already exists".formatted(name));
        }
        Marketplace marketplace = marketplaceRepository.register(
                name, url, forgeMetadataService.resolve(url).orElse(null));
        auditLogger.record(actor, marketplace.name(), "marketplace-registered", null);
        return marketplace;
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

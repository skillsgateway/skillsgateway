package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import io.github.reqstool.annotations.Requirements;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The administrative connector on/off switch (GW_0143), and the only place its resolution rule
 * lives. The rule is deliberately narrow: a connector's effective state for a marketplace is its
 * per-marketplace setting when one exists, otherwise its global setting, otherwise enabled.
 *
 * <p>Reversing the previous stance that the chain had no enable/disable switch is a trust-boundary
 * change (ADR 0009), so the switch is reserved to administrators at the controller, and every
 * change is audited here — the one path both the API and any future declarative reconciliation go
 * through — with the connector, the scope and the new state named. A toggle for a connector name no
 * built-in currently carries is refused rather than stored silently: a typo that matched nothing
 * would be a control an administrator believes is off while it is on.
 */
@Service
public class ConnectorToggleService {

    /** Ledger event when a connector is switched off (GW_0143). */
    public static final String EVENT_DISABLED = "connector-disabled";

    /** Ledger event when a connector is switched back on (GW_0143). */
    public static final String EVENT_ENABLED = "connector-enabled";

    private final ConnectorToggleRepository repository;
    private final MarketplaceRepository marketplaceRepository;
    private final AdminAuditLogger auditLogger;
    private final Set<String> knownConnectors;

    public ConnectorToggleService(
            ConnectorToggleRepository repository,
            MarketplaceRepository marketplaceRepository,
            AdminAuditLogger auditLogger,
            List<VettingConnector> connectors) {
        this.repository = repository;
        this.marketplaceRepository = marketplaceRepository;
        this.auditLogger = auditLogger;
        this.knownConnectors = connectors.stream().map(VettingConnector::name).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Whether a connector runs for one marketplace's chain run (GW_0143): the per-marketplace
     * setting if there is one, otherwise the global setting, otherwise enabled.
     */
    @Requirements({"GW_0143"})
    public boolean enabled(String connector, long marketplaceId) {
        return repository
                .find(connector, marketplaceId)
                .or(() -> repository.findGlobal(connector))
                .map(ConnectorToggle::enabled)
                .orElse(true);
    }

    /**
     * Sets the enablement of a connector globally ({@code marketplaceName} null) or for one
     * marketplace, and writes the change to the ledger. Refuses an unknown connector name and an
     * unknown marketplace name so a mistaken toggle fails loudly instead of matching nothing.
     */
    @Requirements({"GW_0143"})
    public ConnectorToggle set(
            String connector, String marketplaceName, boolean enabled, String reason, String principal) {
        if (connector == null || !knownConnectors.contains(connector)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "unknown connector '%s'; the built-in connectors are %s".formatted(connector, knownConnectors));
        }
        Long marketplaceId = null;
        String scope = "global";
        if (marketplaceName != null && !marketplaceName.isBlank()) {
            Marketplace marketplace = marketplaceRepository
                    .findByName(marketplaceName)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(marketplaceName)));
            marketplaceId = marketplace.id();
            scope = "marketplace(" + marketplaceName + ")";
        }
        ConnectorToggle toggle = repository.set(connector, marketplaceId, enabled, blankToNull(reason), principal);
        // The ledger's marketplace column is NOT NULL; a global toggle uses the "-" placeholder the
        // other global-scope events (grants, estate failures) use.
        auditLogger.record(
                principal,
                marketplaceId == null ? "-" : marketplaceName,
                enabled ? EVENT_ENABLED : EVENT_DISABLED,
                null,
                "connector=%s scope=%s enabled=%s%s"
                        .formatted(
                                connector,
                                scope,
                                enabled,
                                reason == null || reason.isBlank() ? "" : " reason=" + reason));
        return toggle;
    }

    /** Every setting there is. Admin-only at the controller: connector settings are not public. */
    public List<ConnectorToggle> list() {
        return repository.list();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

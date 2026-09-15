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
 * The administrative vetter on/off switch (GW_VETTING_0029.1, GW_VETTING_0029.4), and the only place its resolution rule
 * lives. The rule is deliberately narrow: a vetter's effective state for a marketplace is its
 * per-marketplace setting when one exists, otherwise its global setting, otherwise enabled.
 *
 * <p>Reversing the previous stance that the chain had no enable/disable switch is a trust-boundary
 * change (ADR 0009), so the switch is reserved to administrators at the controller, and every
 * change is audited here — the one path both the API and any future declarative reconciliation go
 * through — with the vetter, the scope and the new state named. A toggle for a vetter name no
 * vetter currently carries is refused rather than stored silently: a typo that matched nothing
 * would be a control an administrator believes is off while it is on.
 *
 * <p>The known set is the injected chain, so an operator's external vetter is switchable on the
 * same terms as a built-in. The guarantees that keep the switch from becoming a blanket approval
 * (GW_VETTING_0029.2 - GW_VETTING_0029.4) are properties of the run, not of which vetter was
 * switched, so they hold for either kind.
 */
@Service
public class VetterToggleService {

    /** Ledger event when a vetter is switched off (GW_VETTING_0029.4). */
    public static final String EVENT_DISABLED = "vetter-disabled";

    /** Ledger event when a vetter is switched back on (GW_VETTING_0029.4). */
    public static final String EVENT_ENABLED = "vetter-enabled";

    private final VetterToggleRepository repository;
    private final MarketplaceRepository marketplaceRepository;
    private final AdminAuditLogger auditLogger;
    private final Set<String> knownVetters;

    public VetterToggleService(
            VetterToggleRepository repository,
            MarketplaceRepository marketplaceRepository,
            AdminAuditLogger auditLogger,
            List<Vetter> vetters) {
        this.repository = repository;
        this.marketplaceRepository = marketplaceRepository;
        this.auditLogger = auditLogger;
        this.knownVetters = vetters.stream().map(Vetter::name).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * A vetter's effective state for one marketplace and the setting that decided it, or a
     * {@link ChainSource#DEFAULT} resolution with no setting when nothing has ever been set.
     */
    public record Resolution(boolean enabled, ChainSource source, VetterToggle setting) {}

    /**
     * The resolution rule (GW_VETTING_0029.1), and the only place it lives: the per-marketplace setting if
     * there is one, otherwise the global setting, otherwise enabled. Returning the deciding setting
     * alongside the answer is what lets the administrative chain view name a default as a default
     * rather than as a decision somebody made.
     */
    @Requirements({"GW_VETTING_0029.1"})
    public Resolution resolve(String vetter, long marketplaceId) {
        return repository
                .find(vetter, marketplaceId)
                .map(toggle -> new Resolution(toggle.enabled(), ChainSource.MARKETPLACE, toggle))
                .or(() -> repository
                        .findGlobal(vetter)
                        .map(toggle -> new Resolution(toggle.enabled(), ChainSource.GLOBAL, toggle)))
                .orElseGet(() -> new Resolution(true, ChainSource.DEFAULT, null));
    }

    /** Whether a vetter runs for one marketplace's chain run (GW_VETTING_0029.1). */
    @Requirements({"GW_VETTING_0029.1"})
    public boolean enabled(String vetter, long marketplaceId) {
        return resolve(vetter, marketplaceId).enabled();
    }

    /**
     * Sets the enablement of a vetter globally ({@code marketplaceName} null) or for one
     * marketplace, and writes the change to the ledger. Refuses an unknown vetter name and an
     * unknown marketplace name so a mistaken toggle fails loudly instead of matching nothing.
     */
    @Requirements({"GW_VETTING_0029.1", "GW_VETTING_0029.4"})
    public VetterToggle set(String vetter, String marketplaceName, boolean enabled, String reason, String principal) {
        if (vetter == null || !knownVetters.contains(vetter)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "unknown vetter '%s'; the configured vetters are %s".formatted(vetter, knownVetters));
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
        VetterToggle toggle = repository.set(vetter, marketplaceId, enabled, blankToNull(reason), principal);
        // The ledger's marketplace column is NOT NULL; a global toggle uses the "-" placeholder the
        // other global-scope events (grants, estate failures) use.
        auditLogger.record(
                principal,
                marketplaceId == null ? "-" : marketplaceName,
                enabled ? EVENT_ENABLED : EVENT_DISABLED,
                null,
                "vetter=%s scope=%s enabled=%s%s"
                        .formatted(
                                vetter, scope, enabled, reason == null || reason.isBlank() ? "" : " reason=" + reason));
        return toggle;
    }

    /** Every setting there is. Admin-only at the controller: vetter settings are not public. */
    public List<VetterToggle> list() {
        return repository.list();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

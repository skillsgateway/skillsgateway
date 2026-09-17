package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The two administrative settings that shape a chain run beyond which vetters are switched on: how
 * far the chain goes (GW_VETTING_0032) and the order it goes in (GW_VETTING_0033).
 *
 * <p>Both resolve by the rule {@link VetterToggleService} established and this class deliberately
 * repeats rather than generalises: the setting scoped to the marketplace when there is one,
 * otherwise the global setting, otherwise the default. The deciding setting travels back with the
 * answer, so an administrative surface can name a default as a default rather than as a decision
 * somebody made.
 *
 * <p>Both are reserved to administrators at the controller, and every change is audited here — the
 * one path the API and any future declarative reconciliation both go through. An order naming a
 * vetter no vetter currently carries is refused rather than stored: an arrangement that matched
 * nothing is a chain an administrator believes they arranged.
 */
@Service
public class VettingChainSettingsService {

    /** Ledger event when the chain mode is set (GW_VETTING_0032.4). */
    public static final String EVENT_MODE_SET = "vetting-chain-mode-set";

    /** Ledger event when the vetter order is set (GW_VETTING_0033.1). */
    public static final String EVENT_ORDER_SET = "vetting-chain-order-set";

    /** The ledger's marketplace column is NOT NULL; a gateway-wide scope uses this placeholder. */
    private static final String GLOBAL_SCOPE = "-";

    private final VettingChainSettingsRepository repository;
    private final MarketplaceRepository marketplaceRepository;
    private final AdminAuditLogger auditLogger;
    private final List<Vetter> vetters;

    public VettingChainSettingsService(
            VettingChainSettingsRepository repository,
            MarketplaceRepository marketplaceRepository,
            AdminAuditLogger auditLogger,
            List<Vetter> vetters) {
        this.repository = repository;
        this.marketplaceRepository = marketplaceRepository;
        this.auditLogger = auditLogger;
        this.vetters = List.copyOf(vetters);
    }

    /** A marketplace's effective chain mode and the setting that decided it. */
    public record ModeResolution(ChainMode mode, ChainSource source, ChainModeSetting setting) {}

    /**
     * A marketplace's effective vetter arrangement and the setting that decided it. {@code override}
     * is what the administrator named, empty for a {@link ChainSource#DEFAULT} resolution; the
     * total order over the whole chain is {@link VetterOrder#resolve}.
     */
    public record OrderResolution(List<String> override, ChainSource source, ChainOrderSetting setting) {}

    /** The resolution rule for the mode (GW_VETTING_0032.1), and the only place it lives. */
    @Requirements({"GW_VETTING_0032.1"})
    public ModeResolution resolveMode(long marketplaceId) {
        return repository
                .findMode(marketplaceId)
                .map(setting -> new ModeResolution(setting.mode(), ChainSource.MARKETPLACE, setting))
                .or(() -> repository
                        .findGlobalMode()
                        .map(setting -> new ModeResolution(setting.mode(), ChainSource.GLOBAL, setting)))
                .orElseGet(() -> new ModeResolution(ChainMode.RUN_ALL, ChainSource.DEFAULT, null));
    }

    /** The resolution rule for the order (GW_VETTING_0033.1), the same rule one level down. */
    @Requirements({"GW_VETTING_0033.1"})
    public OrderResolution resolveOrder(long marketplaceId) {
        return repository
                .findOrder(marketplaceId)
                .map(setting -> new OrderResolution(setting.vetters(), ChainSource.MARKETPLACE, setting))
                .or(() -> repository
                        .findGlobalOrder()
                        .map(setting -> new OrderResolution(setting.vetters(), ChainSource.GLOBAL, setting)))
                .orElseGet(() -> new OrderResolution(List.of(), ChainSource.DEFAULT, null));
    }

    /** The chain of one marketplace, in the order it runs (GW_VETTING_0033). */
    @Requirements({"GW_VETTING_0033"})
    public List<Vetter> orderedVetters(long marketplaceId) {
        return VetterOrder.resolve(vetters, resolveOrder(marketplaceId).override());
    }

    /**
     * Sets the chain mode globally ({@code marketplaceName} null) or for one marketplace, and writes
     * the change to the ledger. An unknown marketplace is refused, and a refused change writes
     * nothing (GW_VETTING_0032.4).
     */
    @Requirements({"GW_VETTING_0032.1", "GW_VETTING_0032.4"})
    public ChainModeSetting setMode(ChainMode mode, String marketplaceName, String reason, String principal) {
        if (mode == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "the 'mode' field is required; the chain modes are %s".formatted(modeNames()));
        }
        Scope scope = scopeOf(marketplaceName);
        ChainModeSetting setting = repository.setMode(scope.marketplaceId(), mode, blankToNull(reason), principal);
        auditLogger.record(
                principal,
                scope.ledgerMarketplace(),
                EVENT_MODE_SET,
                null,
                "mode=%s scope=%s%s".formatted(mode.stored(), scope.description(), reasonSuffix(reason)));
        return setting;
    }

    /**
     * Sets the vetter order globally or for one marketplace, and writes the change to the ledger.
     * An empty order, an unknown vetter name, a name given twice and an unknown marketplace are all
     * refused before anything is stored or audited (GW_VETTING_0033.1).
     */
    @Requirements({"GW_VETTING_0033", "GW_VETTING_0033.1"})
    public ChainOrderSetting setOrder(List<String> requested, String marketplaceName, String reason, String principal) {
        List<String> order = validateOrder(requested);
        Scope scope = scopeOf(marketplaceName);
        ChainOrderSetting setting = repository.setOrder(scope.marketplaceId(), order, blankToNull(reason), principal);
        auditLogger.record(
                principal,
                scope.ledgerMarketplace(),
                EVENT_ORDER_SET,
                null,
                "order=%s scope=%s%s".formatted(String.join(",", order), scope.description(), reasonSuffix(reason)));
        return setting;
    }

    /** Every mode setting there is. Admin-only at the controller, like the toggles. */
    public List<ChainModeSetting> modes() {
        return repository.listModes();
    }

    /** Every order setting there is. Admin-only at the controller, like the toggles. */
    public List<ChainOrderSetting> orders() {
        return repository.listOrders();
    }

    /**
     * The arrangement an administrator asked for, once it is known to name real vetters and to name
     * each of them once. Refusing rather than sanitising is the point: a silently dropped name is a
     * vetter the administrator believes they moved.
     */
    private List<String> validateOrder(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "an order must name at least one vetter; the configured vetters are %s".formatted(vetterNames()));
        }
        Set<String> known = vetterNames();
        Set<String> seen = new LinkedHashSet<>();
        List<String> order = new ArrayList<>(requested.size());
        for (String name : requested) {
            String trimmed = name == null ? "" : name.trim();
            if (!known.contains(trimmed)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "unknown vetter '%s'; the configured vetters are %s".formatted(name, known));
            }
            if (!seen.add(trimmed)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "vetter '%s' is named twice in the order".formatted(trimmed));
            }
            order.add(trimmed);
        }
        return List.copyOf(order);
    }

    private Set<String> vetterNames() {
        return vetters.stream().map(Vetter::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String modeNames() {
        return java.util.Arrays.stream(ChainMode.values())
                .map(ChainMode::stored)
                .toList()
                .toString();
    }

    /** Which scope a request names, resolved once so the store and the ledger cannot disagree. */
    private record Scope(Long marketplaceId, String name) {

        String ledgerMarketplace() {
            return marketplaceId == null ? GLOBAL_SCOPE : name;
        }

        String description() {
            return marketplaceId == null ? "global" : "marketplace(" + name + ")";
        }
    }

    private Scope scopeOf(String marketplaceName) {
        if (marketplaceName == null || marketplaceName.isBlank()) {
            return new Scope(null, null);
        }
        Marketplace marketplace = marketplaceRepository
                .findByName(marketplaceName)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(marketplaceName)));
        return new Scope(marketplace.id(), marketplace.name());
    }

    private static String reasonSuffix(String reason) {
        return reason == null || reason.isBlank() ? "" : " reason=" + reason;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

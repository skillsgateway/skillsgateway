package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * One chain change applied to several marketplaces as a single audited act (GW_VETTING_0037).
 *
 * <p>Two rules shape this class, and both are about what an auditor can reconstruct afterwards.
 *
 * <p><b>Validate whole, apply per marketplace.</b> Everything knowable without touching a
 * marketplace — the action, the mode, the order, the vetter name, which overrides to clear — is
 * refused before anything is stored or recorded, which keeps the "a refused change writes nothing"
 * stance the single-scope paths already hold. What remains is genuinely per-marketplace: a name that
 * no longer resolves fails that marketplace and no other. That is the only way a partial failure
 * arises, and it is kept reachable rather than converted into a whole-request refusal — refusing
 * eight marketplaces because a ninth was deleted is the worse answer mid-rollout, and a success that
 * silently skipped it is worse still.
 *
 * <p><b>Nothing is applied here.</b> Every write goes through the same {@link VetterToggleService}
 * and {@link VettingChainSettingsService} methods a single-scope request goes through, carrying the
 * correlation id. The bulk path therefore cannot drift from the single path in validation, storage,
 * webhook or ledger shape, and the estate-wide act is legible on the ledger as N ordinary entries
 * that share one id rather than as a summary that could disagree with them.
 */
@Service
public class VettingChainBulkService {

    private final VettingChainSettingsService settingsService;
    private final VetterToggleService toggleService;

    public VettingChainBulkService(VettingChainSettingsService settingsService, VetterToggleService toggleService) {
        this.settingsService = settingsService;
        this.toggleService = toggleService;
    }

    @Requirements({"GW_VETTING_0036", "GW_VETTING_0037"})
    public BulkChainResult apply(BulkChainChange request, String principal) {
        if (request == null || request.action() == null || request.action().isBlank()) {
            throw unprocessable("the 'action' field is required (set-mode, set-order, set-vetter or clear)");
        }
        BulkChainChange.Action action = parse(() -> BulkChainChange.Action.of(request.action()));
        List<String> marketplaces = distinctNames(request.marketplaces());
        Plan plan = validate(action, request);

        String correlationId = UUID.randomUUID().toString();
        List<BulkChainResult.Outcome> results = new ArrayList<>(marketplaces.size());
        for (String marketplace : marketplaces) {
            results.add(applyOne(action, plan, request, marketplace, principal, correlationId));
        }
        // The counts are derived from the outcomes rather than tallied alongside them: a summary
        // that could disagree with the list it summarises is the failure this response shape exists
        // to prevent, and two places to increment is how that disagreement arrives.
        return new BulkChainResult(
                correlationId,
                count(results, BulkChainResult.Status.APPLIED),
                count(results, BulkChainResult.Status.UNCHANGED),
                count(results, BulkChainResult.Status.FAILED),
                results);
    }

    /** The request's shape, once it is known to be one the gateway could carry out. */
    private record Plan(ChainMode mode, List<String> order, List<BulkChainChange.Clear> clear) {}

    /**
     * Everything that can be refused without touching a marketplace. Refusing here is what keeps a
     * typo from changing half an estate before it is noticed.
     */
    private Plan validate(BulkChainChange.Action action, BulkChainChange request) {
        return switch (action) {
            case SET_MODE -> {
                if (request.mode() == null || request.mode().isBlank()) {
                    throw unprocessable("the 'mode' field is required (run-all or stop-after-fail)");
                }
                try {
                    yield new Plan(ChainMode.of(request.mode()), null, null);
                } catch (IllegalArgumentException e) {
                    throw unprocessable(e.getMessage());
                }
            }
            case SET_ORDER -> new Plan(null, settingsService.validateOrder(request.vetters()), null);
            case SET_VETTER -> {
                toggleService.requireKnown(request.vetter());
                if (request.enabled() == null) {
                    throw unprocessable("the 'enabled' field is required (true or false)");
                }
                yield new Plan(null, null, null);
            }
            case CLEAR -> {
                if (request.clear() == null || request.clear().isEmpty()) {
                    throw unprocessable("the 'clear' field must name at least one of mode, order or vetters");
                }
                LinkedHashSet<BulkChainChange.Clear> kinds = new LinkedHashSet<>();
                for (String kind : request.clear()) {
                    kinds.add(parse(() -> BulkChainChange.Clear.of(kind)));
                }
                yield new Plan(null, null, List.copyOf(kinds));
            }
        };
    }

    private BulkChainResult.Outcome applyOne(
            BulkChainChange.Action action,
            Plan plan,
            BulkChainChange request,
            String marketplace,
            String principal,
            String correlationId) {
        try {
            return switch (action) {
                case SET_MODE -> {
                    settingsService.setMode(plan.mode(), marketplace, request.reason(), principal, correlationId);
                    yield applied(marketplace, "mode=" + plan.mode().stored());
                }
                case SET_ORDER -> {
                    settingsService.setOrder(plan.order(), marketplace, request.reason(), principal, correlationId);
                    yield applied(marketplace, "order=" + String.join(",", plan.order()));
                }
                case SET_VETTER -> {
                    toggleService.set(
                            request.vetter(),
                            marketplace,
                            request.enabled(),
                            request.reason(),
                            principal,
                            correlationId);
                    yield applied(marketplace, "%s=%s".formatted(request.vetter(), request.enabled()));
                }
                case CLEAR -> clear(plan.clear(), marketplace, request.reason(), principal, correlationId);
            };
        } catch (ResponseStatusException e) {
            // One marketplace's refusal is that marketplace's outcome, not the request's. The reason
            // is the server's own, so the portal can name it rather than invent one.
            return new BulkChainResult.Outcome(marketplace, BulkChainResult.Status.FAILED, e.getReason());
        }
    }

    /**
     * Clearing is idempotent, and a scope that held no override is {@code UNCHANGED} rather than
     * applied: an estate-wide clear over a selection where two of eight actually override should
     * leave two entries on the ledger, not eight records that nothing happened.
     */
    private BulkChainResult.Outcome clear(
            List<BulkChainChange.Clear> kinds,
            String marketplace,
            String reason,
            String principal,
            String correlationId) {
        List<String> cleared = new ArrayList<>();
        for (BulkChainChange.Clear kind : kinds) {
            String removed =
                    switch (kind) {
                        case MODE ->
                            settingsService.clearMode(marketplace, reason, principal, correlationId) ? "mode" : null;
                        case ORDER ->
                            settingsService.clearOrder(marketplace, reason, principal, correlationId) ? "order" : null;
                        case VETTERS -> {
                            int count = toggleService.clearAll(marketplace, reason, principal, correlationId);
                            yield count == 0 ? null : count + " vetter override" + (count == 1 ? "" : "s");
                        }
                    };
            if (removed != null) {
                cleared.add(removed);
            }
        }
        return cleared.isEmpty()
                ? new BulkChainResult.Outcome(
                        marketplace, BulkChainResult.Status.UNCHANGED, "no override of that kind to clear")
                : applied(marketplace, "cleared " + String.join(", ", cleared));
    }

    private static int count(List<BulkChainResult.Outcome> results, BulkChainResult.Status status) {
        return (int)
                results.stream().filter(outcome -> outcome.status() == status).count();
    }

    private static BulkChainResult.Outcome applied(String marketplace, String detail) {
        return new BulkChainResult.Outcome(marketplace, BulkChainResult.Status.APPLIED, detail);
    }

    /**
     * The named marketplaces, blanks refused and duplicates folded. A name given twice would be two
     * ledger entries for one decision, which is the shape this endpoint exists to avoid.
     */
    private static List<String> distinctNames(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw unprocessable("the 'marketplaces' field must name at least one marketplace");
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String name : requested) {
            if (name == null || name.isBlank()) {
                throw unprocessable("a marketplace name in 'marketplaces' is blank");
            }
            names.add(name.trim());
        }
        return List.copyOf(names);
    }

    /** A wire name the gateway does not know is a request it can carry out no part of, so 422. */
    private static <T> T parse(java.util.function.Supplier<T> parser) {
        try {
            return parser.get();
        } catch (IllegalArgumentException e) {
            throw unprocessable(e.getMessage());
        }
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}

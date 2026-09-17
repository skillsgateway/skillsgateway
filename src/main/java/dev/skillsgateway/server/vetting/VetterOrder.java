package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The rule that turns an administrator's partial arrangement into the total order the chain runs in
 * (GW_VETTING_0033.1), and the only place it lives.
 *
 * <p>Named vetters first, in the order the override names them; then every vetter it does not name,
 * by configured position with ties broken by name — the rule that decided the whole chain before
 * overrides existed, unchanged and still the fallback.
 *
 * <p>Appending rather than dropping the unnamed ones is what keeps an override from silently
 * removing a control. An override written today cannot know about a vetter a later release adds or
 * an operator configures tomorrow, and the safe reading of that silence is "it still runs, where it
 * always would have", not "it is gone".
 *
 * <p>Pure and static over the vetter list, so the order is exhaustively testable without a
 * database, a repository or a Spring context.
 */
public final class VetterOrder {

    private VetterOrder() {}

    /** Configured position, ties broken by name: total on its own, and the order with no override. */
    public static final Comparator<Vetter> CONFIGURED =
            Comparator.comparingInt(Vetter::order).thenComparing(Vetter::name);

    /**
     * The chain's order for one marketplace.
     *
     * @param vetters every configured vetter, in any order
     * @param override the names an administrator arranged, or empty for none; a name matching no
     *     configured vetter is ignored here — {@link VettingChainSettingsService} refuses one at the
     *     point it is set, which is where a typo can still be told about
     */
    @Requirements({"GW_VETTING_0033.1"})
    public static List<Vetter> resolve(List<Vetter> vetters, List<String> override) {
        List<Vetter> configured = new ArrayList<>(vetters);
        configured.sort(CONFIGURED);
        if (override == null || override.isEmpty()) {
            return List.copyOf(configured);
        }
        Set<String> wanted = new LinkedHashSet<>(override);
        List<Vetter> ordered = new ArrayList<>(configured.size());
        for (String name : wanted) {
            configured.stream()
                    .filter(vetter -> vetter.name().equals(name))
                    .findFirst()
                    .ifPresent(ordered::add);
        }
        for (Vetter vetter : configured) {
            if (!wanted.contains(vetter.name())) {
                ordered.add(vetter);
            }
        }
        return List.copyOf(ordered);
    }
}

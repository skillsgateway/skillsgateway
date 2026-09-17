package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * One chain change addressed to several marketplaces (GW_VETTING_0037): what to change, what to
 * change it to, and the note that travels onto every ledger entry the request causes.
 *
 * <p>One action per request rather than a list of edits. An estate-wide change is a decision an
 * administrator has to be able to state in a sentence and confirm before it lands; a request that
 * could set a mode for two marketplaces and clear an order for three is a batch, and a batch cannot
 * be confirmed — the before/after screen would have nothing single to show.
 *
 * <p>The action and the override kinds arrive as strings and are parsed by the service rather than
 * by Jackson. A name the gateway does not know is a request it can carry out no part of, which is
 * 422 — the code every other refusal on these settings uses — and a Jackson-side enum would make it
 * a 400 that says nothing about which names exist.
 */
@Schema(description = "One vetting-chain change addressed to several marketplaces")
public record BulkChainChange(
        @Schema(description = "The marketplaces the change applies to; each is attempted independently")
        List<String> marketplaces,

        @Schema(
                description = "Which change to make",
                allowableValues = {"set-mode", "set-order", "set-vetter", "clear"})
        String action,

        @Schema(
                description = "For set-mode: how far the chain should run",
                allowableValues = {"run-all", "stop-after-fail"})
        String mode,

        @Schema(description = "For set-order: the vetter names, in the order they should run")
        List<String> vetters,

        @Schema(description = "For set-vetter: which vetter to switch")
        String vetter,

        @Schema(description = "For set-vetter: whether the vetter should run")
        Boolean enabled,

        // No allowableValues: on a list field the annotation constrains the container rather than
        // the element, which generates a client type that cannot hold the list at all.
        @Schema(description = "For clear: which overrides to remove — any of mode, order, vetters")
        List<String> clear,

        @Schema(description = "Note recorded with every change and on every ledger entry the request causes")
        String reason) {

    /** What a bulk request does. */
    public enum Action {
        SET_MODE("set-mode"),
        SET_ORDER("set-order"),
        SET_VETTER("set-vetter"),
        CLEAR("clear");

        private final String wire;

        Action(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Action of(String value) {
            return lookup(values(), Action::wire, value, "action");
        }
    }

    /** Which kind of per-marketplace override a clear removes. */
    public enum Clear {
        MODE("mode"),
        ORDER("order"),
        VETTERS("vetters");

        private final String wire;

        Clear(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Clear of(String value) {
            return lookup(values(), Clear::wire, value, "override kind");
        }
    }

    private static <T extends Enum<T>> T lookup(T[] values, Function<T, String> wire, String value, String what) {
        String wanted = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (T candidate : values) {
            if (wire.apply(candidate).equals(wanted)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown %s '%s'; the known ones are %s"
                .formatted(what, value, Arrays.stream(values).map(wire).toList()));
    }
}

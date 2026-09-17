package dev.skillsgateway.server.vetting;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;

/**
 * How far a chain run goes (GW_VETTING_0032). Resolved per marketplace, then globally, then to
 * {@link #RUN_ALL} — the rule {@link VetterToggleService} established for the on/off switch.
 *
 * <p>A named mode rather than a boolean, so a third policy can arrive without a second flag
 * contradicting the first.
 */
@Schema(description = "How far a vetting chain run goes")
public enum ChainMode {

    /**
     * Every enabled vetter runs, whatever the ones before it concluded. The default, and the
     * behaviour before the mode existed: a reviewer sees everything that is wrong with a snapshot
     * in one run, and the run's verdict set is complete by construction.
     */
    RUN_ALL("run-all"),

    /**
     * The chain stops after the first vetter whose verdict is {@link VerdictState#FAIL}; the
     * vetters after it are recorded {@link VerdictState#NOT_REACHED} rather than run. Only a
     * failure stops it — not an error, which is a fact about the gateway rather than the content,
     * and not a pending verdict, which has concluded nothing.
     */
    STOP_AFTER_FAIL("stop-after-fail");

    private final String stored;

    ChainMode(String stored) {
        this.stored = stored;
    }

    /**
     * Storage and wire form: the hyphenated spelling, matching the {@code vetting_chain_mode_mode}
     * type. One spelling for the database, the API and the portal, so nothing has to translate.
     */
    @JsonValue
    public String stored() {
        return stored;
    }

    @JsonCreator
    public static ChainMode of(String stored) {
        String wanted = stored == null ? "" : stored.trim().toLowerCase(Locale.ROOT);
        for (ChainMode mode : values()) {
            if (mode.stored.equals(wanted)
                    || mode.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown chain mode '%s'".formatted(stored));
    }
}

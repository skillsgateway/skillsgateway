package io.github.jimisola.skillsgateway.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;

/** How much a finding matters. {@code INFO} never changes a verdict; it is recorded for the record. */
@Schema(description = "How much a finding matters")
public enum Severity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public String stored() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Severity of(String stored) {
        return valueOf(stored.toUpperCase(Locale.ROOT));
    }

    public boolean atLeast(Severity other) {
        return ordinal() >= other.ordinal();
    }
}

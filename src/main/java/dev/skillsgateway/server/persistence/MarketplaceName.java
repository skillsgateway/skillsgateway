package dev.skillsgateway.server.persistence;

import io.github.reqstool.annotations.Requirements;
import java.util.regex.Pattern;

/**
 * The marketplace name rule, defined once: the name is the facade path segment {@code /git/<name>}
 * and the marketplace's only identifier. Registration, the facade and the {@code marketplaces.name}
 * CHECK in {@code V1__init.sql} must agree; the portal mirrors it in {@code form-rules.ts}.
 */
@Requirements({"GW_INGEST_0063", "GW_INGEST_0064"})
public final class MarketplaceName {

    /** A DNS label's bound. */
    public static final int MAX_LENGTH = 63;

    /** The rule as a regex; also the OpenAPI {@code pattern}. */
    public static final String REGEX = "^[a-z0-9][a-z0-9_-]{0," + (MAX_LENGTH - 1) + "}$";

    public static final Pattern PATTERN = Pattern.compile(REGEX);

    /** The refusal, in the words a registrant reads; the portal's field error says the same. */
    public static final String RULE = "name must be 1 to " + MAX_LENGTH
            + " lowercase letters, digits, - or _, starting with a letter or digit,"
            + " because it is the /git/<name> path your clients clone";

    private MarketplaceName() {}

    public static boolean isValid(String name) {
        return name != null && PATTERN.matcher(name).matches();
    }
}

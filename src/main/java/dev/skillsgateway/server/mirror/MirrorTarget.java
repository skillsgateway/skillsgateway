package dev.skillsgateway.server.mirror;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.time.Duration;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * A validated mirror configuration (GW_FACADE_0020): the one marketplace this increment mirrors, where it
 * is pushed, and with what.
 *
 * <p>Not a record, because a record's generated {@code toString} would print the push credential
 * into the first log line or exception message that mentioned it. The credential is reachable only
 * through {@link #credentials()}, which hands it to JGit and to nothing else.
 *
 * <p>Validation happens once, when the target is built at startup, and nothing here contacts the
 * mirror: what is checked is the operator's configuration, not the forge's availability. That
 * distinction is what keeps an invalid configuration a loud startup failure while an unreachable
 * mirror stays a contained runtime event (GW_FACADE_0021).
 */
public final class MirrorTarget {

    private final String marketplace;
    private final String url;
    private final String username;
    private final String token;
    private final Duration timeout;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final boolean sweepEnabled;

    private MirrorTarget(
            String marketplace,
            String url,
            String username,
            String token,
            Duration timeout,
            int maxAttempts,
            Duration retryDelay,
            boolean sweepEnabled) {
        this.marketplace = marketplace;
        this.url = url;
        this.username = username;
        this.token = token;
        this.timeout = timeout;
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
        this.sweepEnabled = sweepEnabled;
    }

    /**
     * The configured target, or null when the mirror is disabled — which is the shipped default and
     * the state in which nothing in this package does anything at all.
     *
     * @throws IllegalStateException when the mirror is enabled but its configuration is not usable.
     *     Refusing to start is deliberate: the alternative, disabling the mirror and carrying on,
     *     leaves an operator who asked for a mirror with no mirror and no signal, and a
     *     configuration mistake is not the outage this class is otherwise built to absorb.
     */
    @Requirements({"GW_FACADE_0020"})
    public static MirrorTarget from(SkillsGatewayProperties properties) {
        SkillsGatewayProperties.Mirror mirror = properties.mirror();
        if (!mirror.enabled()) {
            return null;
        }
        if (mirror.marketplace() == null || mirror.marketplace().isBlank()) {
            throw new IllegalStateException("skills-gateway.mirror.marketplace is required when the mirror is enabled");
        }
        String refusal = MirrorUrlPolicy.refuse(mirror.url(), properties.allowedUrlSchemes());
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        return new MirrorTarget(
                mirror.marketplace(),
                mirror.url(),
                mirror.username(),
                mirror.token(),
                mirror.timeout(),
                mirror.maxAttempts(),
                mirror.retryDelay(),
                mirror.sweepEnabled());
    }

    public String marketplace() {
        return marketplace;
    }

    /** Safe to print: {@link MirrorUrlPolicy} refuses a URL that embeds a credential. */
    public String url() {
        return url;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration retryDelay() {
        return retryDelay;
    }

    /** Whether the recurring reconciliation runs (GW_FACADE_0025). On by default, with the mirror. */
    public boolean sweepEnabled() {
        return sweepEnabled;
    }

    /**
     * How long a caller asking for a reconciliation now should be willing to wait (GW_FACADE_0027): every
     * attempt's transport timeout plus every delay between them. Bounded by construction — the
     * settings are validated at startup — so an administrator's request cannot hang on a forge that
     * has stopped answering.
     */
    Duration attemptBudget() {
        return timeout.plus(retryDelay).multipliedBy(maxAttempts);
    }

    /** Null when no credential is configured, which is what an anonymous or local transport wants. */
    CredentialsProvider credentials() {
        return username == null || username.isBlank() ? null : new UsernamePasswordCredentialsProvider(username, token);
    }

    /** Timeouts are seconds on the JGit transports; a sub-second setting still gets one try. */
    int timeoutSeconds() {
        return Math.max(1, (int) timeout.toSeconds());
    }

    @Override
    public String toString() {
        return "MirrorTarget[marketplace=%s, url=%s]".formatted(marketplace, url);
    }
}

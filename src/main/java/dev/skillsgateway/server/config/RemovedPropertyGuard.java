package dev.skillsgateway.server.config;

import dev.skillsgateway.server.config.RemovedProperties.Disposition;
import dev.skillsgateway.server.config.RemovedProperties.Removal;
import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup's answer to a manifest that still sets a property the gateway removed (GW_AUTH_0027).
 *
 * <p>One pass over {@link RemovedProperties#ALL}, in the shape {@code DevInsecureAuthGuard}
 * established for the sibling escape hatch — say what was detected, why it matters, and the way
 * out, quoting property names in full. Driven from the map rather than from one hand-written check
 * per property: the check that gets forgotten is always the one somebody had to remember to add,
 * and a list is something a reviewer can read in one place and see the whole of.
 *
 * <p>Relaxed binding means one {@code containsProperty} call per entry covers every spelling the
 * framework would have resolved — {@code skills-gateway.roles.enabled},
 * {@code SKILLSGATEWAY_ROLES_ENABLED} and the rest — so this list never has to be kept in step with
 * a second list of spellings.
 *
 * <p>Every refusal is reported together. An operator upgrading across several versions has more
 * than one stale line, and a guard that stops at the first turns one edit into a sequence of failed
 * starts.
 */
@Component
@Requirements({"GW_AUTH_0027"})
public class RemovedPropertyGuard {

    private static final Logger log = LoggerFactory.getLogger(RemovedPropertyGuard.class);

    private static final String MIGRATION_AID =
            "This is a migration aid and is scheduled for removal at the next major version.";

    /**
     * The removed properties this deployment still sets and that were ignored rather than refused,
     * kept for anything that wants to report the deployment's shape. Empty is the ordinary case.
     */
    private final List<String> ignored;

    public RemovedPropertyGuard(Environment environment) {
        this.ignored = check(environment, RemovedProperties.ALL);
    }

    /** The stale property names this start warned about, in the order they were reported. */
    public List<String> ignored() {
        return ignored;
    }

    /**
     * The whole guard, as a function of the environment and the list, returning what it ignored. A
     * static seam rather than a second constructor: two constructors leave the container with no
     * way to choose one, and the dispositions have to be exercisable against entries that are not
     * today's.
     */
    static List<String> check(Environment environment, Map<String, Removal> removed) {
        List<String> refusals = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        // Sorted, so the report an operator reads is stable between starts rather than in whatever
        // order the map happened to iterate.
        new TreeMap<>(removed).forEach((path, removal) -> {
            if (!environment.containsProperty(path)) {
                return;
            }
            if (removal.disposition() == Disposition.REFUSE) {
                refusals.add(refusal(path, removal));
            } else {
                log.warn("{}", ignored(path, removal));
                ignored.add(path);
            }
        });
        if (!refusals.isEmpty()) {
            throw new IllegalStateException(String.join("\n\n", refusals));
        }
        return List.copyOf(ignored);
    }

    private static String refusal(String path, Removal removal) {
        return """
                %s was removed, and this gateway refuses to start while it is set.

                %s

                Remove %s. %s""".formatted(path, removal.advice(), path, MIGRATION_AID);
    }

    private static String ignored(String path, Removal removal) {
        return """
                %s was removed and this gateway is ignoring it.

                %s

                Remove %s. %s""".formatted(path, removal.advice(), path, MIGRATION_AID);
    }
}

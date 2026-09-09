package dev.skillsgateway.server.config;

import java.util.Map;

/**
 * Every configuration property this gateway used to read, and what setting one now means.
 *
 * <p>A removed property is not the same as an unknown one. An operator who sets a name the gateway
 * never had made a typo; an operator who sets a name the gateway <em>used</em> to honour is
 * carrying a manifest forward from a version where it did something, and silence tells them it
 * still does. This is the list, in one place, so that removing a property is a decision with a
 * disposition attached rather than a deletion with a release note.
 *
 * <p>Entries are a migration aid and are scheduled for removal at the next major version.
 */
final class RemovedProperties {

    /** What setting a removed property does to startup. */
    enum Disposition {
        /**
         * Startup stops. For a property whose removal an operator could read as the opposite of
         * what they asked for — anything that used to switch a control off or raise a bound. A
         * gateway quietly doing the safer thing is still a gateway doing something other than what
         * the manifest says, and that is not visible from inside the deployment.
         */
        REFUSE,

        /**
         * Startup continues and says so. For a property whose removal costs latency or throughput
         * and nothing else — an interval, a batch size. Refusing there converts a leftover line in
         * a manifest into an outage, which is a worse trade than a log line nobody reads.
         */
        WARN
    }

    /**
     * @param disposition whether a deployment that still sets this starts
     * @param advice what to do instead, in full sentences and naming the properties that replace it
     */
    record Removal(Disposition disposition, String advice) {}

    static final Map<String, Removal> ALL =
            Map.of("skills-gateway.roles.enabled", new Removal(Disposition.REFUSE, """
                    Authorization is now always enforced, so there is nothing for this property to \
                    turn on or off. Ignoring it silently would be worse than refusing: a deployment \
                    that set it to false was asking for a gateway with no authorization, and quietly \
                    doing the opposite of what a manifest says is not something an operator can see \
                    from inside the deployment.

                    If the intention was to keep a gateway that anyone who logs in can administer, \
                    that is no longer available; grant the admin role to the principals who should \
                    have it instead, with skills-gateway.roles.admins or a \
                    skills-gateway.roles.mappings entry."""));

    private RemovedProperties() {}
}

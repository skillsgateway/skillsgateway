package dev.skillsgateway.server.ingestion;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.storage.RefTransitions;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fetches the external plugin sources a manifest declares and this gateway's configuration admits
 * (GW_0155), into the quarantine repository of the marketplace being ingested.
 *
 * <p>Quarantine is the only place unapproved content may land, and it is never served, so the
 * fetched content is invisible to clients until {@link ManifestRewriter} has grafted it into a
 * composite commit and a reviewer has approved that commit. The references this holds the fetched
 * tips on are scaffolding: they are removed in a {@code finally}, so a failure leaves the fetched
 * objects unreachable rather than leaving a half-resolved state behind for the next pass to find.
 *
 * <p>Every fetch goes through {@link GuardedHttpConnectionFactory}, installed on that one transport
 * rather than on JGit's JVM-wide static, and every source is measured against
 * {@link ResolutionBudget} before it is accepted. Any refusal ends the whole resolution: a manifest
 * is resolved completely or not at all, which is what GW_0161 turns into "the snapshot is rejected"
 * and GW_0152 relies on.
 */
@Component
public class ExternalSourceResolver {

    /** Where fetched tips are held while the composite is built. Pruned before this returns. */
    static final String SCAFFOLD_PREFIX = "refs/plugin-sources/";

    private static final Logger log = LoggerFactory.getLogger(ExternalSourceResolver.class);

    private final SkillsGatewayProperties properties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ExternalSourceResolver(SkillsGatewayProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** The clock directly, so a test can cross the deadline rather than wait for it. */
    ExternalSourceResolver(SkillsGatewayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** One source, fetched and pinned. */
    public record Resolved(String pluginName, String cloneUrl, ObjectId sha, ObjectId tree) {}

    /**
     * Either every source resolved, or the reason none of them count. There is deliberately no
     * partial outcome: a caller cannot accidentally proceed with some of a manifest resolved.
     */
    public record Resolution(List<Resolved> resolved, String violation) {

        static Resolution refused(String violation) {
            return new Resolution(List.of(), violation);
        }

        public boolean rejected() {
            return violation != null;
        }
    }

    @Requirements({"GW_0155", "GW_0157", "GW_0158"})
    public Resolution resolve(Repository quarantine, List<ManifestPolicy.Admitted> admitted) {
        if (admitted.isEmpty()) {
            return new Resolution(List.of(), null);
        }
        SkillsGatewayProperties.ExternalSources config = properties.ingestion().externalSources();
        ResolutionBudget budget = new ResolutionBudget(config.budgets(), clock);
        SourceUrlPolicy urlPolicy = new SourceUrlPolicy(
                Set.copyOf(properties.allowedUrlSchemes().stream()
                        .map(scheme -> scheme.toLowerCase(Locale.ROOT))
                        .toList()),
                budget.maxRedirects());
        SourceAddressPolicy addressPolicy = new SourceAddressPolicy(config.allowPrivateNetworks());

        // Identical sources are fetched once. That is also the visited set a later increment needs
        // if the closure ever goes deeper than one level.
        Map<String, Resolved> byUrl = new LinkedHashMap<>();
        List<Resolved> resolved = new ArrayList<>();
        try {
            for (ManifestPolicy.Admitted source : admitted) {
                String expired = budget.expired();
                if (expired != null) {
                    return Resolution.refused(expired);
                }
                Resolved already = byUrl.get(source.cloneUrl());
                if (already != null) {
                    resolved.add(new Resolved(source.pluginName(), already.cloneUrl(), already.sha(), already.tree()));
                    continue;
                }
                Fetched fetched = fetch(quarantine, source, urlPolicy, addressPolicy, budget, byUrl.size());
                if (fetched.violation() != null) {
                    return Resolution.refused(fetched.violation());
                }
                String overBudget = budget.accept(source.pluginName(), fetched.measurement());
                if (overBudget != null) {
                    return Resolution.refused(overBudget);
                }
                byUrl.put(source.cloneUrl(), fetched.resolved());
                resolved.add(fetched.resolved());
            }
            return new Resolution(List.copyOf(resolved), null);
        } finally {
            pruneScaffolding(quarantine);
        }
    }

    private record Fetched(Resolved resolved, ResolutionBudget.Measurement measurement, String violation) {

        static Fetched refused(String violation) {
            return new Fetched(null, null, violation);
        }
    }

    private Fetched fetch(
            Repository quarantine,
            ManifestPolicy.Admitted source,
            SourceUrlPolicy urlPolicy,
            SourceAddressPolicy addressPolicy,
            ResolutionBudget budget,
            int slot) {
        String cloneUrl = source.cloneUrl();
        String targetRefusal = urlPolicy.refuseTarget(cloneUrl);
        if (targetRefusal != null) {
            return Fetched.refused(refusal(source, targetRefusal));
        }
        String scaffoldRef = SCAFFOLD_PREFIX + slot;
        int timeoutSeconds = (int) Math.max(1, remaining(budget).toSeconds());
        GuardedHttpConnectionFactory guard = new GuardedHttpConnectionFactory(
                urlPolicy,
                addressPolicy,
                cloneUrl,
                budget.maxReceivedBytes(),
                timeoutSeconds * 1000,
                timeoutSeconds * 1000);
        try (Git git = new Git(quarantine)) {
            harden(
                            git.fetch()
                                    .setRemote(cloneUrl)
                                    .setRefSpecs(new RefSpec("+" + Constants.HEAD + ":" + scaffoldRef))
                                    .setCheckFetchedObjects(true),
                            guard,
                            timeoutSeconds)
                    .call();
        } catch (GitAPIException | RuntimeException e) {
            if (guard.violation() != null) {
                return Fetched.refused(refusal(source, guard.violation()));
            }
            log.debug("external source fetch failed for {}: {}", cloneUrl, e.toString());
            return Fetched.refused(refusal(source, "the source could not be fetched (" + rootMessage(e) + ")"));
        }
        try {
            ObjectId sha = quarantine.resolve(scaffoldRef);
            if (sha == null) {
                return Fetched.refused(refusal(source, "the source produced no commit"));
            }
            try (RevWalk walk = new RevWalk(quarantine)) {
                RevCommit commit = walk.parseCommit(sha);
                ResolutionBudget.Measurement measurement = measure(quarantine, commit, guard.receivedBytes());
                return new Fetched(
                        new Resolved(
                                source.pluginName(),
                                cloneUrl,
                                sha,
                                commit.getTree().getId()),
                        measurement,
                        null);
            }
        } catch (IOException e) {
            return Fetched.refused(refusal(source, "the fetched content could not be read (" + rootMessage(e) + ")"));
        }
    }

    /**
     * Puts the gateway's own policy on this one transport. Never
     * {@code HttpTransport.setConnectionFactory}: that is a JVM-wide static, and a fetch elsewhere
     * in the process silently inheriting an ingestion policy is the kind of coupling nobody finds
     * until it refuses something unrelated.
     */
    private static <C extends TransportCommand<C, T>, T> C harden(
            C command, GuardedHttpConnectionFactory guard, int timeoutSeconds) {
        command.setTimeout(timeoutSeconds);
        command.setTransportConfigCallback(transport -> {
            if (transport instanceof TransportHttp http) {
                http.setHttpConnectionFactory(guard);
            }
        });
        return command;
    }

    /**
     * What the fetched commit's content costs, walked once. This measures the tree that will be
     * grafted rather than the whole fetched history: it is the content vetting, the inventory and
     * the diff will all walk, and the received-byte budget is what bounds the transfer itself.
     */
    private static ResolutionBudget.Measurement measure(Repository repository, RevCommit commit, long receivedBytes)
            throws IOException {
        long objects = 0;
        long inflated = 0;
        long largestBlob = 0;
        int depth = 0;
        try (ObjectReader reader = repository.newObjectReader();
                TreeWalk walk = new TreeWalk(repository, reader)) {
            walk.addTree(commit.getTree());
            walk.setRecursive(false);
            while (walk.next()) {
                objects++;
                depth = Math.max(depth, walk.getDepth() + 1);
                if (walk.isSubtree()) {
                    walk.enterSubtree();
                    continue;
                }
                long size = reader.getObjectSize(walk.getObjectId(0), Constants.OBJ_BLOB);
                inflated += size;
                largestBlob = Math.max(largestBlob, size);
            }
        }
        return new ResolutionBudget.Measurement(receivedBytes, inflated, objects, largestBlob, depth);
    }

    private Duration remaining(ResolutionBudget budget) {
        Duration configured = properties.ingestion().externalSources().budgets().deadline();
        return configured.isZero() ? Duration.ofSeconds(1) : configured;
    }

    /**
     * The scaffolding goes whether the resolution succeeded or not. On success the content is
     * reachable from the composite commit; on failure it becomes unreachable, which is what
     * "no partial graft" means in a repository where objects are written before they are named.
     */
    private static void pruneScaffolding(Repository quarantine) {
        try {
            Set<String> refs = new HashSet<>();
            for (Ref ref : quarantine.getRefDatabase().getRefsByPrefix(SCAFFOLD_PREFIX)) {
                refs.add(ref.getName());
            }
            for (String ref : refs) {
                RefTransitions.delete(quarantine, ref);
            }
        } catch (IOException e) {
            // Worth knowing about and not worth failing an ingestion over: a reference left here
            // serves nothing (quarantine is never served) and is overwritten by the next pass.
            log.warn("could not prune external source scaffolding refs: {}", e.toString());
        }
    }

    private static String refusal(ManifestPolicy.Admitted source, String reason) {
        return "plugin '%s' declares an external source (%s) that could not be resolved: %s"
                .formatted(source.pluginName(), source.cloneUrl(), reason);
    }

    /**
     * The failure, reduced to text safe to record as a snapshot violation.
     *
     * <p>Sanitised, not merely trimmed. A transport failure's message can contain bytes the remote
     * sent — JGit quotes the response it could not parse — so an unsanitised message carries
     * attacker-controlled content into a violation column, a portal page and the audit ledger. A
     * NUL in it is not even storable: PostgreSQL refuses the insert, which turns a rejected snapshot
     * into a failed ingestion. Found by the flood case in
     * {@code ExternalSourceResolutionTests}, which is exactly the shape that produces one.
     */
    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < message.length() && safe.length() < 200; i++) {
            char character = message.charAt(i);
            safe.append(character < 0x20 || character == 0x7F ? ' ' : character);
        }
        return safe.toString().trim();
    }
}

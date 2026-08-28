package dev.skillsgateway.server.storage;

import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PackParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves every repository from one storage backend to another, then proves it moved.
 *
 * <p>Offline and one-shot, deliberately. The gateway is scaled to zero, this copies quarantine,
 * hosted and published for every marketplace the <em>source</em> holds, re-reads both sides and
 * compares the resolved reference sets, and refuses to report success on any disagreement. Nothing
 * is re-approved and nothing is re-ingested: the bytes move, the decisions do not.
 *
 * <p>The source is opened and never written. That is what makes the whole operation reversible:
 * the old volume is still exactly what it was, so switching {@code skills-gateway.storage.backend}
 * back and starting again restores the previous state precisely, and the reverse migration is the
 * same command with the two ends swapped.
 *
 * <p>Objects move as a pack stream through a temporary file rather than through memory —
 * {@link PackWriter} on the source, {@link PackParser} on the target — because a marketplace
 * repository is bounded but not small, and a migration that needed the largest repository to fit
 * in the heap would fail on exactly the estate that most needs migrating.
 */
public final class StorageMigration {

    private static final Logger log = LoggerFactory.getLogger(StorageMigration.class);

    /** The results a forced reference write reports when the reference is what we asked for after. */
    private static final Set<RefUpdate.Result> WRITTEN = Set.of(
            RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.NO_CHANGE);

    /** One repository's outcome: what moved, and what the verification pass found. */
    public record RepositoryResult(GitStorage.Role role, String marketplace, int refs, List<String> mismatches) {

        public boolean verified() {
            return mismatches.isEmpty();
        }
    }

    /** What the whole run did, in the order it did it. */
    public record Report(List<RepositoryResult> repositories) {

        public boolean verified() {
            return repositories.stream().allMatch(RepositoryResult::verified);
        }

        public List<RepositoryResult> failures() {
            return repositories.stream().filter(r -> !r.verified()).toList();
        }

        /** A refusal that names every repository that did not survive the copy intact. */
        public String refusal() {
            StringBuilder message = new StringBuilder("storage migration is NOT complete: ")
                    .append(failures().size())
                    .append(" of ")
                    .append(repositories.size())
                    .append(" repositories did not verify. The source backend is untouched, so nothing has been"
                            + " lost and nothing should be discarded; the destination must not be adopted.");
            for (RepositoryResult failure : failures()) {
                message.append(System.lineSeparator())
                        .append("  ")
                        .append(failure.role().path())
                        .append('/')
                        .append(failure.marketplace())
                        .append(": ")
                        .append(String.join("; ", failure.mismatches()));
            }
            return message.toString();
        }
    }

    private final Path scratch;

    /**
     * @param scratch a directory for the pack stream in flight; created if absent, and everything
     *     it holds is temporary by construction
     */
    public StorageMigration(Path scratch) {
        this.scratch = scratch;
    }

    /**
     * Copy every repository the source holds into the target, then verify each one.
     *
     * <p>Verification is a second, independent read of both sides rather than a claim made by the
     * copy about itself: the resolved reference map of the destination has to equal the source's,
     * reference for reference and object id for object id, and the head reference has to point at
     * the same branch. A repository that does not match is named, and the run reports failure —
     * success is never the default outcome of having finished.
     */
    @Requirements({"GW_0114"})
    public Report migrate(GitStorage source, GitStorage target) throws IOException {
        Files.createDirectories(scratch);
        List<RepositoryResult> results = new ArrayList<>();
        for (GitStorage.Role role : GitStorage.Role.values()) {
            for (String marketplace : new TreeSet<>(source.marketplaces(role))) {
                results.add(migrateOne(source, target, role, marketplace));
            }
        }
        Report report = new Report(List.copyOf(results));
        if (report.verified()) {
            log.info(
                    "storage migration copied and verified {} repositories",
                    report.repositories().size());
        } else {
            log.error("{}", report.refusal());
        }
        return report;
    }

    private RepositoryResult migrateOne(GitStorage source, GitStorage target, GitStorage.Role role, String marketplace)
            throws IOException {
        Map<String, ObjectId> expected;
        Map<String, String> expectedLinks;
        try (Repository from = source.open(role, marketplace);
                Repository to = target.open(role, marketplace)) {
            expected = resolved(from);
            expectedLinks = symbolic(from);
            copyObjects(from, to, expected.values());
            writeRefs(to, expected, expectedLinks);
        }
        // Re-opened, so what is checked is what the destination will actually serve rather than
        // the in-memory state the copy happened to leave behind. On a DFS backend that also means
        // the manifest is read again from the store.
        List<String> mismatches;
        try (Repository from = source.open(role, marketplace);
                Repository to = target.open(role, marketplace)) {
            mismatches = compare(expected, expectedLinks, resolved(to), symbolic(to));
            // The source must be byte-identical afterwards; a copy that moved a reference on the
            // side it was reading would be a migration that damaged the thing it was rescuing.
            if (!resolved(from).equals(expected) || !symbolic(from).equals(expectedLinks)) {
                mismatches = new ArrayList<>(mismatches);
                mismatches.add("the source repository changed during the migration");
            }
        }
        return new RepositoryResult(role, marketplace, expected.size(), List.copyOf(mismatches));
    }

    /**
     * Every <em>concrete</em> reference, resolved to an object id.
     *
     * <p>Symbolic references are excluded here and carried by {@link #symbolic} instead, and the
     * separation is load-bearing rather than tidy. {@code getRefs()} reports a symbolic reference
     * with the object id it resolves to, so treating {@code HEAD} as an ordinary reference and
     * writing it through {@code updateRef(name)} silently dereferences it and writes whatever
     * branch the <em>destination's</em> head happened to point at. On a freshly created repository
     * that is {@code refs/heads/main}, so a marketplace published on any other branch grew a
     * spurious {@code main} at the destination while looking correct on every repository the
     * gateway itself had made.
     */
    private static Map<String, ObjectId> resolved(Repository repository) throws IOException {
        Map<String, ObjectId> refs = new TreeMap<>();
        for (Ref ref : repository.getRefDatabase().getRefs()) {
            ObjectId id = ref.getObjectId();
            if (!ref.isSymbolic() && id != null) {
                refs.put(ref.getName(), id.copy());
            }
        }
        return refs;
    }

    /** Every symbolic reference and what it is linked to, {@code HEAD} included. */
    private static Map<String, String> symbolic(Repository repository) throws IOException {
        Map<String, String> links = new TreeMap<>();
        for (Ref ref : repository.getRefDatabase().getRefs()) {
            if (ref.isSymbolic()) {
                links.put(ref.getName(), ref.getTarget().getName());
            }
        }
        Ref head = repository.exactRef(Constants.HEAD);
        if (head != null && head.isSymbolic()) {
            links.put(Constants.HEAD, head.getTarget().getName());
        }
        return links;
    }

    private void copyObjects(Repository from, Repository to, java.util.Collection<ObjectId> tips) throws IOException {
        if (tips.isEmpty()) {
            return;
        }
        ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
        Path pack = Files.createTempFile(scratch, "migration-", ".pack");
        try {
            try (PackWriter writer = new PackWriter(from);
                    OutputStream out = Files.newOutputStream(pack)) {
                // Not thin, and no cached packs: the destination is empty of these objects, so
                // there is nothing to delta against and nothing it could already have.
                writer.setThin(false);
                writer.setUseCachedPacks(false);
                writer.preparePack(monitor, new HashSet<>(tips), Set.of());
                writer.writePack(monitor, monitor, out);
            }
            try (ObjectInserter inserter = to.newObjectInserter();
                    InputStream in = Files.newInputStream(pack)) {
                PackParser parser = inserter.newPackParser(in);
                parser.setAllowThin(false);
                parser.parse(monitor);
                inserter.flush();
            }
        } finally {
            Files.deleteIfExists(pack);
        }
    }

    /**
     * Write the source's references onto the destination. Concrete references first and the
     * symbolic head last, so the head is never linked to a branch that does not exist yet.
     */
    private static void writeRefs(Repository to, Map<String, ObjectId> concrete, Map<String, String> links)
            throws IOException {
        for (Map.Entry<String, ObjectId> ref : new LinkedHashMap<>(concrete).entrySet()) {
            RefUpdate update = to.updateRef(ref.getKey());
            update.setNewObjectId(ref.getValue());
            update.setForceUpdate(true);
            RefUpdate.Result result = update.update();
            if (!WRITTEN.contains(result)) {
                throw new IOException(
                        "could not write %s at the migration destination: %s".formatted(ref.getKey(), result));
            }
        }
        for (Map.Entry<String, String> link : links.entrySet()) {
            // Detached, so the update names the symbolic reference itself rather than whatever the
            // destination's copy of it currently resolves to.
            RefUpdate.Result result = to.updateRef(link.getKey(), true).link(link.getValue());
            if (!WRITTEN.contains(result)) {
                throw new IOException("could not link %s to %s at the migration destination: %s"
                        .formatted(link.getKey(), link.getValue(), result));
            }
        }
    }

    private static List<String> compare(
            Map<String, ObjectId> expected,
            Map<String, String> expectedLinks,
            Map<String, ObjectId> actual,
            Map<String, String> actualLinks) {
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, ObjectId> ref : expected.entrySet()) {
            ObjectId there = actual.get(ref.getKey());
            if (there == null) {
                mismatches.add(ref.getKey() + " is missing at the destination");
            } else if (!there.equals(ref.getValue())) {
                mismatches.add("%s resolves to %s at the destination, not %s"
                        .formatted(ref.getKey(), there.name(), ref.getValue().name()));
            }
        }
        for (String name : actual.keySet()) {
            if (!expected.containsKey(name)) {
                mismatches.add(name + " exists at the destination and not at the source");
            }
        }
        for (Map.Entry<String, String> link : expectedLinks.entrySet()) {
            String there = actualLinks.get(link.getKey());
            if (!link.getValue().equals(there)) {
                mismatches.add("%s is linked to %s at the destination, not %s"
                        .formatted(link.getKey(), there, link.getValue()));
            }
        }
        for (String name : actualLinks.keySet()) {
            if (!expectedLinks.containsKey(name)) {
                mismatches.add(name + " is a symbolic reference at the destination and not at the source");
            }
        }
        return mismatches;
    }
}

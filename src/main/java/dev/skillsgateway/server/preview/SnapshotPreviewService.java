package dev.skillsgateway.server.preview;

import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.springframework.stereotype.Service;

/**
 * Read-only inspection of a snapshot's pinned content (GW_INGEST_0015, GW_INGEST_0016): the file tree and
 * individual blobs of exactly the commit the snapshot pins, and the diff against the
 * marketplace's currently served commit. Everything resolves through the quarantine
 * repository's object store — paths are addressed only within the pinned commit's tree via
 * JGit tree walks, never through the filesystem, so there is no traversal surface.
 *
 * <p>Inspection, not execution: content is returned as text for rendering only, cut at
 * {@link #MAX_TEXT_BYTES} with an explicit truncation marker, and a blob detected as binary is
 * returned as metadata without text. The published repository is opened only to resolve the
 * baseline SHA of the served tip; no quarantine content flows anywhere the facade can reach.
 *
 * <p>Every listing is bounded per request and paged by offset, with the size of the whole set
 * stated beside the page (GW_APPROVAL_0025, GW_APPROVAL_0026): a response stays small, the snapshot
 * has no ceiling.
 */
@Service
public class SnapshotPreviewService {

    /** Per-blob text cap: defends the reviewer's browser, not a policy anyone tunes. */
    static final int MAX_TEXT_BYTES = 128 * 1024;

    /** Page size of the flat listing and of path search. */
    static final int FILES_PAGE = 2000;

    /** Page size of one directory's children. */
    static final int TREE_PAGE = 500;

    /** Page size of the diff; each entry keeps its own text cap. */
    static final int DIFF_PAGE = 500;

    private final GitStorage storage;
    private final SnapshotRepository snapshotRepository;
    private final MarketplaceRepository marketplaceRepository;

    public SnapshotPreviewService(
            GitStorage storage, SnapshotRepository snapshotRepository, MarketplaceRepository marketplaceRepository) {
        this.storage = storage;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
    }

    @Schema(description = "One path in the pinned commit's tree")
    public record TreeEntry(
            @Schema(description = "Path within the snapshot")
            String path,

            @Schema(description = "Blob size in bytes") long size) {}

    @Schema(description = "A page of the pinned commit's paths, optionally narrowed by a path search")
    public record FileTree(
            @Schema(description = "Snapshot id") long snapshotId,
            @Schema(description = "Pinned commit SHA") String sha,

            @Schema(description = "Paths in this page, in tree order")
            List<TreeEntry> entries,

            @Schema(description = "True when this page does not hold every matching path; see nextOffset")
            boolean truncated,

            @Schema(description = "How many paths match, over the whole snapshot rather than this page")
            int total,

            @Schema(description = "The offset of the next page, or null when this page reaches the end")
            Integer nextOffset) {}

    @Schema(description = "One direct child of a listed directory")
    public record TreeChild(
            @Schema(description = "Last path segment") String name,

            @Schema(description = "Full path within the snapshot")
            String path,

            @Schema(
                    description = "Whether the child is a file or a directory",
                    allowableValues = {"file", "directory"})
            String kind,

            @Schema(description = "Blob size in bytes; null for a directory and for a path the snapshot removes")
            Long size,

            @Schema(
                    description = "How the path stands against the served commit; null when identical, and"
                            + " for a directory. With nothing served, every path is added.",
                    allowableValues = {"added", "modified", "removed"})
            String status,

            @Schema(description = "For a directory: files beneath it in the snapshot; null for a file")
            Integer files,

            @Schema(
                    description = "For a directory: paths beneath it that differ from the served commit,"
                            + " removed ones included; null for a file")
            Integer changed) {}

    @Schema(description = "One directory of the pinned commit: a page of its direct children, with totals")
    public record DirectoryListing(
            @Schema(description = "Snapshot id") long snapshotId,
            @Schema(description = "Pinned commit SHA") String sha,

            @Schema(description = "The served commit the statuses are against, or null when nothing is served")
            String baselineSha,

            @Schema(description = "The listed directory; empty for the root")
            String dir,

            @Schema(description = "Files beneath the directory in the snapshot, at any depth")
            int files,

            @Schema(description = "Paths beneath the directory that differ from the served commit, removed included")
            int changed,

            @Schema(description = "Direct children in this page: directories first, then files, each by name")
            List<TreeChild> entries,

            @Schema(description = "True when this page does not hold every child; see nextOffset")
            boolean truncated,

            @Schema(description = "How many direct children the directory has")
            int total,

            @Schema(description = "The offset of the next page, or null when this page reaches the end")
            Integer nextOffset) {}

    @Schema(description = "One blob of the pinned commit, as text for rendering only")
    public record FileContent(
            @Schema(description = "Snapshot id") long snapshotId,

            @Schema(description = "Path within the snapshot")
            String path,

            @Schema(description = "Full blob size in bytes") long size,

            @Schema(description = "True for a blob detected as binary; such a blob carries no text")
            boolean binary,

            @Schema(description = "True when the text was cut at the per-file size limit")
            boolean truncated,

            @Schema(description = "Blob content as UTF-8 text, or null for a binary blob")
            String text) {}

    @Schema(description = "One changed path between the served baseline and the pinned commit")
    public record DiffEntryView(
            @Schema(description = "Path within the snapshot")
            String path,

            @Schema(
                    description = "How the path changed relative to the served baseline",
                    allowableValues = {"added", "modified", "removed"})
            String type,

            @Schema(description = "True when either side is binary; such an entry carries no diff text")
            boolean binary,

            @Schema(description = "True when the diff text was cut at the per-file size limit")
            boolean truncated,

            @Schema(description = "Unified text diff, or null for a binary entry or when no baseline is served")
            String diff) {}

    @Schema(description = "Counts over the whole diff, not the page")
    public record FileDiffSummary(
            @Schema(description = "Paths added") int added,
            @Schema(description = "Paths modified") int modified,
            @Schema(description = "Paths removed") int removed,

            @Schema(description = "Changed paths with a binary side, which carry no line counts")
            int binary,

            @Schema(description = "Lines added across every text entry; zero when nothing is served")
            long linesAdded,

            @Schema(description = "Lines removed across every text entry; zero when nothing is served")
            long linesRemoved) {

        static final FileDiffSummary EMPTY = new FileDiffSummary(0, 0, 0, 0, 0, 0);
    }

    @Schema(description = "The snapshot's delta against the marketplace's currently served commit, a page at a time")
    public record SnapshotDiff(
            @Schema(description = "Snapshot id") long snapshotId,
            @Schema(description = "Pinned commit SHA") String sha,

            @Schema(description = "The served commit the diff is against, or null when nothing is served")
            String baselineSha,

            @Schema(description = "Changed paths in this page; with no baseline, the snapshot's paths, all added")
            List<DiffEntryView> entries,

            @Schema(description = "True when this page does not hold every changed path; see nextOffset")
            boolean truncated,

            @Schema(description = "How many paths changed, over the whole (path-narrowed) diff")
            int total,

            @Schema(description = "The offset of the next page, or null when this page reaches the end")
            Integer nextOffset,

            @Schema(description = "Counts over the whole (path-narrowed) diff")
            FileDiffSummary summary) {}

    /** The start of the page after one that began at {@code offset} and held {@code size} of {@code total}. */
    private static Integer next(int offset, int size, int total) {
        // Compared by subtraction: the offset is caller-supplied, so offset + size may overflow.
        return offset < total && size < total - offset ? offset + size : null;
    }

    /** The end of a page of at most {@code page} items that starts at {@code offset} in a list of {@code size}. */
    private static int pageEnd(int offset, int page, int size) {
        return offset < size && page < size - offset ? offset + page : size;
    }

    /**
     * The pinned commit's paths, a page at a time, optionally only those whose full path contains
     * {@code query} regardless of case. {@code total} counts every match, not the page.
     */
    @Requirements({"GW_INGEST_0015", "GW_APPROVAL_0026", "GW_APPROVAL_0027"})
    public FileTree files(long snapshotId, String query, int offset) {
        Resolved resolved = resolve(snapshotId);
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        try (Repository repo = storage.quarantine(resolved.marketplace().name());
                RevWalk walk = new RevWalk(repo)) {
            RevCommit commit =
                    walk.parseCommit(ObjectId.fromString(resolved.snapshot().sha()));
            List<TreeEntry> entries = new ArrayList<>();
            int matched = 0;
            try (ObjectReader reader = repo.newObjectReader();
                    TreeWalk tree = new TreeWalk(repo)) {
                tree.addTree(commit.getTree());
                tree.setRecursive(true);
                while (tree.next()) {
                    String path = tree.getPathString();
                    if (!needle.isEmpty() && !path.toLowerCase(Locale.ROOT).contains(needle)) {
                        continue;
                    }
                    if (matched++ >= offset && entries.size() < FILES_PAGE) {
                        long size = reader.getObjectSize(tree.getObjectId(0), Constants.OBJ_BLOB);
                        entries.add(new TreeEntry(path, size));
                    }
                }
            }
            Integer nextOffset = next(offset, entries.size(), matched);
            return new FileTree(
                    resolved.snapshot().id(),
                    resolved.snapshot().sha(),
                    List.copyOf(entries),
                    nextOffset != null,
                    matched,
                    nextOffset);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list files of snapshot %d".formatted(snapshotId), e);
        }
    }

    /** A direct child being assembled from the leaves beneath it. */
    private static final class ChildBuilder {
        final String name;
        final String path;
        final boolean directory;
        ObjectId blob;
        String status;
        int files;
        int changed;

        ChildBuilder(String name, String path, boolean directory) {
            this.name = name;
            this.path = path;
            this.directory = directory;
        }
    }

    /**
     * One directory of the pinned commit: its direct children, each with its change against the
     * served tip, and for each child directory the files and changes beneath it. Paths the
     * snapshot removes are children too — they are the part of a delta a listing of the snapshot
     * alone would hide. Empty when {@code dir} is in neither tree or names a file: the directory
     * is matched against tree entries and nothing else, so every traversal shape is absent.
     */
    @Requirements({"GW_APPROVAL_0025", "GW_APPROVAL_0026"})
    public Optional<DirectoryListing> tree(long snapshotId, String dir, int offset) {
        Resolved resolved = resolve(snapshotId);
        String prefix = dir == null ? "" : stripSlashes(dir);
        String baselineSha = servedTip(resolved.marketplace().name());
        try (Repository repo = storage.quarantine(resolved.marketplace().name());
                RevWalk revWalk = new RevWalk(repo);
                ObjectReader reader = repo.newObjectReader();
                TreeWalk walk = new TreeWalk(repo)) {
            RevCommit commit =
                    revWalk.parseCommit(ObjectId.fromString(resolved.snapshot().sha()));
            walk.addTree(commit.getTree());
            if (baselineSha == null) {
                walk.addTree(new EmptyTreeIterator());
            } else {
                walk.addTree(
                        revWalk.parseCommit(ObjectId.fromString(baselineSha)).getTree());
            }
            walk.setRecursive(true);
            if (!prefix.isEmpty()) {
                walk.setFilter(PathFilter.create(prefix));
            }
            String under = prefix.isEmpty() ? "" : prefix + "/";
            boolean found = prefix.isEmpty();
            Map<String, ChildBuilder> children = new LinkedHashMap<>();
            int files = 0;
            int changed = 0;
            while (walk.next()) {
                String path = walk.getPathString();
                if (!path.startsWith(under)) {
                    continue; // the prefix itself, as a file: not a directory to list
                }
                found = true;
                boolean inSnapshot = walk.getFileMode(0) != FileMode.MISSING;
                String status = status(walk, inSnapshot);
                files += inSnapshot ? 1 : 0;
                changed += status == null ? 0 : 1;
                String relative = path.substring(under.length());
                int slash = relative.indexOf('/');
                if (slash < 0) {
                    ChildBuilder file = new ChildBuilder(relative, path, false);
                    file.status = status;
                    file.blob = inSnapshot && walk.getFileMode(0) != FileMode.GITLINK ? walk.getObjectId(0) : null;
                    children.put("f:" + relative, file);
                } else {
                    String name = relative.substring(0, slash);
                    ChildBuilder directory =
                            children.computeIfAbsent("d:" + name, key -> new ChildBuilder(name, under + name, true));
                    directory.files += inSnapshot ? 1 : 0;
                    directory.changed += status == null ? 0 : 1;
                }
            }
            if (!found) {
                return Optional.empty();
            }
            List<ChildBuilder> ordered = children.values().stream()
                    .sorted(Comparator.comparing((ChildBuilder child) -> !child.directory)
                            .thenComparing(child -> child.name))
                    .toList();
            List<TreeChild> page = new ArrayList<>();
            for (ChildBuilder child :
                    ordered.subList(Math.min(offset, ordered.size()), pageEnd(offset, TREE_PAGE, ordered.size()))) {
                page.add(
                        child.directory
                                ? new TreeChild(
                                        child.name, child.path, "directory", null, null, child.files, child.changed)
                                : new TreeChild(
                                        child.name,
                                        child.path,
                                        "file",
                                        child.blob == null
                                                ? null
                                                : reader.getObjectSize(child.blob, Constants.OBJ_BLOB),
                                        child.status,
                                        null,
                                        null));
            }
            Integer nextOffset = next(offset, page.size(), ordered.size());
            return Optional.of(new DirectoryListing(
                    resolved.snapshot().id(),
                    resolved.snapshot().sha(),
                    baselineSha,
                    prefix,
                    files,
                    changed,
                    List.copyOf(page),
                    nextOffset != null,
                    ordered.size(),
                    nextOffset));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list %s of snapshot %d".formatted(prefix, snapshotId), e);
        }
    }

    /** How the walk's current leaf stands: tree 0 is the snapshot, tree 1 the served commit (or empty). */
    private static String status(TreeWalk walk, boolean inSnapshot) {
        boolean served = walk.getFileMode(1) != FileMode.MISSING;
        if (inSnapshot && !served) {
            return "added";
        }
        if (!inSnapshot) {
            return served ? "removed" : null;
        }
        boolean same = walk.idEqual(0, 1) && walk.getRawMode(0) == walk.getRawMode(1);
        return same ? null : "modified";
    }

    private static String stripSlashes(String path) {
        String stripped = path;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    /**
     * One blob of the pinned commit. The path is matched against tree entries and nothing else
     * — a path the tree does not contain, which includes every traversal shape, is absent, and
     * absent answers not-found.
     */
    @Requirements({"GW_INGEST_0015"})
    public Optional<FileContent> file(long snapshotId, String path) {
        Resolved resolved = resolve(snapshotId);
        try (Repository repo = storage.quarantine(resolved.marketplace().name());
                RevWalk walk = new RevWalk(repo)) {
            RevCommit commit =
                    walk.parseCommit(ObjectId.fromString(resolved.snapshot().sha()));
            try (TreeWalk tree = TreeWalk.forPath(repo, path, commit.getTree())) {
                if (tree == null) {
                    return Optional.empty();
                }
                return Optional.of(read(repo, resolved.snapshot().id(), path, tree.getObjectId(0)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read %s of snapshot %d".formatted(path, snapshotId), e);
        }
    }

    /**
     * The delta a reviewer decides: pinned commit vs the marketplace's currently served commit,
     * a page at a time, with counts over the whole of it. The baseline SHA is resolved from the
     * published repository's served tip — the same read the facade serves from — and the diff
     * itself runs inside the quarantine repository, where both commits' objects live. When
     * nothing is served the answer is the honest one: no baseline, every path added.
     *
     * <p>{@code path}, when given, narrows the diff the way a git pathspec does: exactly that file,
     * or everything beneath that directory. It is a filter over tree entries, so a path in
     * neither tree — any traversal shape included — narrows the diff to nothing.
     */
    @Requirements({"GW_INGEST_0016", "GW_APPROVAL_0026", "GW_APPROVAL_0028"})
    public SnapshotDiff diff(long snapshotId, String path, int offset) {
        Resolved resolved = resolve(snapshotId);
        String baselineSha = servedTip(resolved.marketplace().name());
        long id = resolved.snapshot().id();
        String sha = resolved.snapshot().sha();
        String narrowed = path == null ? "" : stripSlashes(path);
        if (sha.equals(baselineSha)) {
            return new SnapshotDiff(id, sha, baselineSha, List.of(), false, 0, null, FileDiffSummary.EMPTY);
        }
        try (Repository repo = storage.quarantine(resolved.marketplace().name());
                RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
            if (baselineSha == null) {
                return allAdded(repo, id, sha, commit, narrowed, offset);
            }
            RevCommit baseline = walk.parseCommit(ObjectId.fromString(baselineSha));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<DiffEntryView> entries = new ArrayList<>();
            try (DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repo);
                if (!narrowed.isEmpty()) {
                    formatter.setPathFilter(PathFilter.create(narrowed));
                }
                // Rename detection stays off: an approval review wants "this path changed",
                // not similarity heuristics.
                List<DiffEntry> changes = formatter.scan(baseline.getTree(), commit.getTree());
                int added = 0;
                int modified = 0;
                int removed = 0;
                int binaries = 0;
                long linesAdded = 0;
                long linesRemoved = 0;
                int end = pageEnd(offset, DIFF_PAGE, changes.size());
                for (int i = 0; i < changes.size(); i++) {
                    DiffEntry change = changes.get(i);
                    String type = type(change);
                    switch (type) {
                        case "added" -> added++;
                        case "removed" -> removed++;
                        default -> modified++;
                    }
                    boolean binary = isBinary(repo, change);
                    if (binary) {
                        binaries++;
                    } else {
                        for (Edit edit : formatter.toFileHeader(change).toEditList()) {
                            linesAdded += edit.getLengthB();
                            linesRemoved += edit.getLengthA();
                        }
                    }
                    if (i >= offset && i < end) {
                        entries.add(view(formatter, out, change, type, binary));
                    }
                }
                Integer nextOffset = next(offset, entries.size(), changes.size());
                return new SnapshotDiff(
                        id,
                        sha,
                        baselineSha,
                        List.copyOf(entries),
                        nextOffset != null,
                        changes.size(),
                        nextOffset,
                        new FileDiffSummary(added, modified, removed, binaries, linesAdded, linesRemoved));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot diff snapshot %d against %s".formatted(snapshotId, baselineSha), e);
        }
    }

    /** Nothing is served: every path of the (narrowed) snapshot, added, with no text and no line counts. */
    private static SnapshotDiff allAdded(
            Repository repo, long id, String sha, RevCommit commit, String narrowed, int offset) throws IOException {
        List<DiffEntryView> entries = new ArrayList<>();
        int total = 0;
        try (TreeWalk tree = new TreeWalk(repo)) {
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
            if (!narrowed.isEmpty()) {
                tree.setFilter(PathFilter.create(narrowed));
            }
            while (tree.next()) {
                if (total++ >= offset && entries.size() < DIFF_PAGE) {
                    entries.add(new DiffEntryView(tree.getPathString(), "added", false, false, null));
                }
            }
        }
        Integer nextOffset = next(offset, entries.size(), total);
        return new SnapshotDiff(
                id,
                sha,
                null,
                List.copyOf(entries),
                nextOffset != null,
                total,
                nextOffset,
                new FileDiffSummary(total, 0, 0, 0, 0, 0));
    }

    private static String type(DiffEntry change) {
        return switch (change.getChangeType()) {
            case ADD -> "added";
            case DELETE -> "removed";
            default -> "modified";
        };
    }

    private static boolean isBinary(Repository repo, DiffEntry change) throws IOException {
        return sideIsBinary(repo, change.getNewId().toObjectId())
                || sideIsBinary(repo, change.getOldId().toObjectId());
    }

    private static DiffEntryView view(
            DiffFormatter formatter, ByteArrayOutputStream out, DiffEntry change, String type, boolean binary)
            throws IOException {
        String path = "removed".equals(type) ? change.getOldPath() : change.getNewPath();
        if (binary) {
            return new DiffEntryView(path, type, true, false, null);
        }
        out.reset();
        formatter.format(change);
        formatter.flush();
        byte[] bytes = out.toByteArray();
        boolean truncated = bytes.length > MAX_TEXT_BYTES;
        String diff = new String(bytes, 0, Math.min(bytes.length, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
        return new DiffEntryView(path, type, false, truncated, diff);
    }

    private static boolean sideIsBinary(Repository repo, ObjectId objectId) throws IOException {
        if (objectId == null || ObjectId.zeroId().equals(objectId)) {
            return false;
        }
        ObjectLoader loader = repo.open(objectId, Constants.OBJ_BLOB);
        try (InputStream in = loader.openStream()) {
            byte[] head = in.readNBytes(8000);
            return RawText.isBinary(head, head.length, loader.getSize() <= head.length);
        }
    }

    private static FileContent read(Repository repo, long snapshotId, String path, ObjectId objectId)
            throws IOException {
        ObjectLoader loader = repo.open(objectId, Constants.OBJ_BLOB);
        long size = loader.getSize();
        byte[] head;
        try (InputStream in = loader.openStream()) {
            head = in.readNBytes(MAX_TEXT_BYTES);
        }
        boolean complete = size <= head.length;
        if (RawText.isBinary(head, head.length, complete)) {
            return new FileContent(snapshotId, path, size, true, false, null);
        }
        return new FileContent(snapshotId, path, size, false, !complete, new String(head, StandardCharsets.UTF_8));
    }

    /** The served tip: {@code refs/heads/main} of the published repository, or null when nothing serves. */
    private String servedTip(String marketplaceName) {
        try {
            Optional<Repository> published = storage.publishedIfServing(marketplaceName);
            if (published.isEmpty()) {
                return null;
            }
            try (Repository repo = published.get()) {
                ObjectId tip = repo.resolve("refs/heads/main");
                return tip == null ? null : tip.name();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot resolve served tip of '%s'".formatted(marketplaceName), e);
        }
    }

    private record Resolved(Snapshot snapshot, Marketplace marketplace) {}

    private Resolved resolve(long snapshotId) {
        Snapshot snapshot =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        Marketplace marketplace = marketplaceRepository
                .findById(snapshot.marketplaceId())
                .orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        return new Resolved(snapshot, marketplace);
    }
}

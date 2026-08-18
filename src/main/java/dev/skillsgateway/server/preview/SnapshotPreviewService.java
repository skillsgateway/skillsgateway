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
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Service;

/**
 * Read-only inspection of a snapshot's pinned content (GW_0080, GW_0081): the file tree and
 * individual blobs of exactly the commit the snapshot pins, and the diff against the
 * marketplace's currently served commit. Everything resolves through the quarantine
 * repository's object store — paths are addressed only within the pinned commit's tree via
 * JGit tree walks, never through the filesystem, so there is no traversal surface.
 *
 * <p>Inspection, not execution: content is returned as text for rendering only, cut at
 * {@link #MAX_TEXT_BYTES} with an explicit truncation marker, and a blob detected as binary is
 * returned as metadata without text. The published repository is opened only to resolve the
 * baseline SHA of the served tip; no quarantine content flows anywhere the facade can reach.
 */
@Service
public class SnapshotPreviewService {

    /** Per-blob text cap: defends the reviewer's browser, not a policy anyone tunes. */
    static final int MAX_TEXT_BYTES = 128 * 1024;

    /** Tree-listing cap; the listing carries an explicit marker when it is cut. */
    static final int MAX_TREE_ENTRIES = 2000;

    /** Diff-entry cap; the diff carries an explicit marker when it is cut. */
    static final int MAX_DIFF_ENTRIES = 500;

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

    @Schema(description = "The file tree of exactly the commit the snapshot pins")
    public record FileTree(
            @Schema(description = "Snapshot id") long snapshotId,
            @Schema(description = "Pinned commit SHA") String sha,

            @Schema(description = "Paths in the pinned commit's tree")
            List<TreeEntry> entries,

            @Schema(description = "True when the listing was cut at the tree-size limit")
            boolean truncated) {}

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

    @Schema(description = "The snapshot's delta against the marketplace's currently served commit")
    public record SnapshotDiff(
            @Schema(description = "Snapshot id") long snapshotId,
            @Schema(description = "Pinned commit SHA") String sha,

            @Schema(description = "The served commit the diff is against, or null when nothing is served")
            String baselineSha,

            @Schema(description = "Changed paths; with no baseline, every path of the snapshot, all added")
            List<DiffEntryView> entries,

            @Schema(description = "True when the entry list was cut at the diff-size limit")
            boolean truncated) {}

    /** The file tree of the pinned commit, bounded and marked when cut. */
    @Requirements({"GW_0080"})
    public FileTree files(long snapshotId) {
        Resolved resolved = resolve(snapshotId);
        try (Repository repo = storage.quarantine(resolved.marketplace().name());
                RevWalk walk = new RevWalk(repo)) {
            RevCommit commit =
                    walk.parseCommit(ObjectId.fromString(resolved.snapshot().sha()));
            List<TreeEntry> entries = new ArrayList<>();
            boolean truncated = false;
            try (ObjectReader reader = repo.newObjectReader();
                    TreeWalk tree = new TreeWalk(repo)) {
                tree.addTree(commit.getTree());
                tree.setRecursive(true);
                while (tree.next()) {
                    if (entries.size() >= MAX_TREE_ENTRIES) {
                        truncated = true;
                        break;
                    }
                    long size = reader.getObjectSize(tree.getObjectId(0), Constants.OBJ_BLOB);
                    entries.add(new TreeEntry(tree.getPathString(), size));
                }
            }
            return new FileTree(resolved.snapshot().id(), resolved.snapshot().sha(), List.copyOf(entries), truncated);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list files of snapshot %d".formatted(snapshotId), e);
        }
    }

    /**
     * One blob of the pinned commit. The path is matched against tree entries and nothing else
     * — a path the tree does not contain, which includes every traversal shape, is absent, and
     * absent answers not-found.
     */
    @Requirements({"GW_0080"})
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
     * The delta a reviewer decides: pinned commit vs the marketplace's currently served commit.
     * The baseline SHA is resolved from the published repository's served tip — the same read
     * the facade serves from — and the diff itself runs inside the quarantine repository, where
     * both commits' objects live. When nothing is served the answer is the honest one: no
     * baseline, every path added.
     */
    @Requirements({"GW_0081"})
    public SnapshotDiff diff(long snapshotId) {
        Resolved resolved = resolve(snapshotId);
        String baselineSha = servedTip(resolved.marketplace().name());
        long id = resolved.snapshot().id();
        String sha = resolved.snapshot().sha();
        if (sha.equals(baselineSha)) {
            return new SnapshotDiff(id, sha, baselineSha, List.of(), false);
        }
        try (Repository repo = storage.quarantine(resolved.marketplace().name());
                RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
            if (baselineSha == null) {
                FileTree tree = files(snapshotId);
                List<DiffEntryView> added = tree.entries().stream()
                        .map(entry -> new DiffEntryView(entry.path(), "added", false, false, null))
                        .toList();
                return new SnapshotDiff(id, sha, null, added, tree.truncated());
            }
            RevCommit baseline = walk.parseCommit(ObjectId.fromString(baselineSha));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<DiffEntryView> entries = new ArrayList<>();
            boolean truncated = false;
            try (DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repo);
                // Rename detection stays off: an approval review wants "this path changed",
                // not similarity heuristics.
                List<DiffEntry> changes = formatter.scan(baseline.getTree(), commit.getTree());
                for (DiffEntry change : changes) {
                    if (entries.size() >= MAX_DIFF_ENTRIES) {
                        truncated = true;
                        break;
                    }
                    entries.add(view(repo, formatter, out, change));
                }
            }
            return new SnapshotDiff(id, sha, baselineSha, List.copyOf(entries), truncated);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot diff snapshot %d against %s".formatted(snapshotId, baselineSha), e);
        }
    }

    private DiffEntryView view(Repository repo, DiffFormatter formatter, ByteArrayOutputStream out, DiffEntry change)
            throws IOException {
        String type =
                switch (change.getChangeType()) {
                    case ADD -> "added";
                    case DELETE -> "removed";
                    default -> "modified";
                };
        String path = "removed".equals(type) ? change.getOldPath() : change.getNewPath();
        boolean binary = sideIsBinary(repo, change.getNewId().toObjectId())
                || sideIsBinary(repo, change.getOldId().toObjectId());
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

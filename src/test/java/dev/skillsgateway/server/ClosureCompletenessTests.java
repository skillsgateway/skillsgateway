package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.approval.ClosureIncompleteException;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;

/**
 * The closure-completeness gate (GW_APPROVAL_0013). Nothing in the gateway can produce an incomplete
 * closure, so every refusal below is arranged by hand: rows removed or rewritten through SQL, and
 * a commit synthesised in quarantine that the closure does not describe. The gate exists so that
 * nothing ever can produce one — including the code that has not been written yet — and these
 * are the proof that it would notice.
 */
class ClosureCompletenessTests extends AbstractExternalSourceTest {

    private static final String REFUSED = "snapshot-approval-refused";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @SVCs({"SVC_GW_APPROVAL_0013"})
    void a_member_removed_from_the_closure_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        deleteMembers(composite.snapshot().id());

        assertRefused(composite, "tools");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0013"})
    void a_composite_with_no_closure_at_all_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        jdbc.sql("DELETE FROM snapshot_closures WHERE snapshot_id = :id")
                .param("id", composite.snapshot().id())
                .update();

        assertRefused(composite, "tools");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0013"})
    void a_member_whose_recorded_tree_differs_from_the_grafted_tree_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        // A real tree — the upstream root — so this is "the wrong content", not "not a tree".
        String otherTree = commit(composite.marketplace(), composite.upstreamHead())
                .getTree()
                .name();
        updateMembers(composite.snapshot().id(), "tree_sha", otherTree);

        assertRefused(composite, "_plugins/tools");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0013"})
    void a_member_re_pathed_away_from_its_graft_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        updateMembers(composite.snapshot().id(), "graft_path", "_plugins/elsewhere");

        assertRefused(composite, "_plugins/elsewhere");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0013"})
    void a_member_whose_resolved_commit_is_not_an_object_id_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        updateMembers(composite.snapshot().id(), "resolved_sha", "not-a-commit");

        assertRefused(composite, "not-a-commit");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0013"})
    void a_snapshot_re_pointed_at_a_commit_without_the_grafts_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        // The composite's parent: same marketplace, no _plugins tree, the manifest still external.
        repoint(composite.snapshot().id(), composite.upstreamHead());

        assertRefused(composite, "_plugins/tools");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0013"})
    void a_stray_entry_under_the_reserved_directory_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        String withStray = commitWithStrayGraft(
                composite.marketplace(), composite.snapshot().sha());
        repoint(composite.snapshot().id(), withStray);

        assertRefused(composite, "_plugins/stray");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0013"})
    void a_manifest_grafting_a_plugin_nothing_resolved_refuses_the_approval() throws Exception {
        // The one direction the tree checks cannot see: the manifest points at a graft that neither
        // the closure nor the commit holds, so only manifest-against-closure notices.
        Composite composite = ingestComposite("gate");
        String ghosted =
                commitWithManifest(composite.marketplace(), composite.snapshot().sha(), manifest -> {
                    ((ArrayNode) manifest.get("plugins"))
                            .addObject()
                            .put("name", "ghost")
                            .put("source", "./_plugins/ghost");
                    return manifest;
                });
        repoint(composite.snapshot().id(), ghosted);

        assertRefused(composite, "ghost");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0013"})
    void a_manifest_that_no_longer_declares_a_recorded_member_refuses_the_approval() throws Exception {
        // The closure and the tree still agree about tools; the manifest has been turned to point
        // elsewhere. Provenance would then describe content the manifest no longer serves.
        Composite composite = ingestComposite("gate");
        String redirected =
                commitWithManifest(composite.marketplace(), composite.snapshot().sha(), manifest -> {
                    ((ObjectNode) manifest.get("plugins").get(1)).put("source", "./plugins/tools");
                    return manifest;
                });
        repoint(composite.snapshot().id(), redirected);

        assertRefused(composite, "_plugins/tools");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0013"})
    void an_administrative_override_does_not_lift_the_gate() throws Exception {
        Composite composite = ingestComposite("gate");
        deleteMembers(composite.snapshot().id());

        assertThatThrownBy(() -> approvalService.approve(
                        composite.snapshot().id(),
                        "admin",
                        ApprovalService.ApprovalOverride.ofVettingFailure("accepting the vetting outcome")))
                .isInstanceOf(ClosureIncompleteException.class);
        assertThat(snapshotRepository
                        .findById(composite.snapshot().id())
                        .orElseThrow()
                        .state())
                .isEqualTo(Snapshot.HELD);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0013"})
    void a_revoked_snapshot_is_checked_again_on_re_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        long id = composite.snapshot().id();
        approve(id);
        assertThat(snapshotRepository.revoke(id, "sweep", "re-vetting found something"))
                .isPresent();
        deleteMembers(id);

        assertThatThrownBy(() -> approve(id)).isInstanceOf(ClosureIncompleteException.class);
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.REVOKED);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0013"})
    void an_untouched_composite_and_a_local_only_snapshot_still_approve() throws Exception {
        Composite composite = ingestComposite("gate");
        Registered local = registerAndIngest(uniqueName("gate"), createUpstream(DEFAULT_MANIFEST));

        assertThat(approve(composite.snapshot().id()).state()).isEqualTo(Snapshot.APPROVED);
        assertThat(approve(local.snapshot().id()).state()).isEqualTo(Snapshot.APPROVED);
        assertThat(ledger(composite.marketplace(), REFUSED)).isEmpty();
    }

    /** Refused with the dedicated outcome, on the ledger, still held, nothing published. */
    private void assertRefused(Composite composite, String named) throws IOException {
        long id = composite.snapshot().id();
        assertThatThrownBy(() -> approve(id))
                .isInstanceOf(ClosureIncompleteException.class)
                .hasMessageContaining(named)
                .satisfies(refused -> assertThat(((ClosureIncompleteException) refused).discrepancies())
                        .isNotEmpty()
                        .anySatisfy(discrepancy -> assertThat(discrepancy).contains(named)));

        Snapshot after = snapshotRepository.findById(id).orElseThrow();
        assertThat(after.state()).isEqualTo(Snapshot.HELD);
        assertThat(after.decidedBy()).isNull();
        assertThat(ledger(composite.marketplace(), REFUSED)).singleElement().satisfies(detail -> assertThat(detail)
                .startsWith("closure-incomplete:")
                .contains(named));
        try (Repository published = storage.published(composite.marketplace())) {
            assertThat(published.resolve("refs/heads/main")).isNull();
        }
    }

    private List<String> ledger(String marketplace, String event) {
        return fetchLogRepository.list().stream()
                .filter(row -> marketplace.equals(row.get("marketplace")) && event.equals(row.get("event")))
                .map(row -> (String) row.get("detail"))
                .toList();
    }

    private void deleteMembers(long snapshotId) {
        jdbc.sql("DELETE FROM snapshot_closure_members WHERE closure_id IN"
                        + " (SELECT id FROM snapshot_closures WHERE snapshot_id = :id)")
                .param("id", snapshotId)
                .update();
    }

    private void updateMembers(long snapshotId, String column, String value) {
        int updated = jdbc.sql("UPDATE snapshot_closure_members SET " + column + " = :value WHERE closure_id IN"
                        + " (SELECT id FROM snapshot_closures WHERE snapshot_id = :id)")
                .param("value", value)
                .param("id", snapshotId)
                .update();
        assertThat(updated).isEqualTo(1);
    }

    private void repoint(long snapshotId, String sha) {
        int updated = jdbc.sql("UPDATE snapshots SET sha = :sha WHERE id = :id")
                .param("sha", sha)
                .param("id", snapshotId)
                .update();
        assertThat(updated).isEqualTo(1);
    }

    /**
     * The composite's tree plus one file under {@code _plugins/stray/}, committed in quarantine
     * with the composite as parent. The manifest and the closure both still describe only
     * {@code tools}; the commit now holds content neither accounts for.
     */
    private String commitWithStrayGraft(String marketplace, String compositeSha) throws IOException {
        return derive(marketplace, compositeSha, (inserter, quarantine, composite) -> {
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, "stray\n".getBytes(StandardCharsets.UTF_8));
            return blobAt("_plugins/stray/README.md", blob);
        });
    }

    /** The composite with its manifest rewritten, committed in quarantine with the composite as parent. */
    private String commitWithManifest(String marketplace, String compositeSha, UnaryOperator<ObjectNode> change)
            throws IOException {
        return derive(marketplace, compositeSha, (inserter, quarantine, composite) -> {
            try (TreeWalk manifest = TreeWalk.forPath(quarantine, MANIFEST_PATH, composite.getTree())) {
                ObjectNode parsed = (ObjectNode)
                        MAPPER.readTree(quarantine.open(manifest.getObjectId(0)).getBytes());
                byte[] rewritten = MAPPER.writeValueAsBytes(change.apply(parsed));
                return blobAt(MANIFEST_PATH, inserter.insert(Constants.OBJ_BLOB, rewritten));
            }
        });
    }

    private static DirCacheEditor.PathEdit blobAt(String path, ObjectId blob) {
        return new DirCacheEditor.PathEdit(path) {
            @Override
            public void apply(DirCacheEntry entry) {
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(blob);
            }
        };
    }

    @FunctionalInterface
    private interface Tampering {
        DirCacheEditor.PathEdit edit(ObjectInserter inserter, Repository quarantine, RevCommit composite)
                throws IOException;
    }

    /** A commit derived from the composite by one path edit, parented on it, in quarantine. */
    private String derive(String marketplace, String compositeSha, Tampering tampering) throws IOException {
        try (Repository quarantine = storage.quarantine(marketplace);
                ObjectReader reader = quarantine.newObjectReader();
                ObjectInserter inserter = quarantine.newObjectInserter()) {
            RevCommit composite = commit(marketplace, compositeSha);
            DirCache cache = DirCache.newInCore();
            DirCacheBuilder builder = cache.builder();
            builder.addTree(new byte[0], DirCacheEntry.STAGE_0, reader, composite.getTree());
            builder.finish();
            DirCacheEditor editor = cache.editor();
            editor.add(tampering.edit(inserter, quarantine, composite));
            editor.finish();
            ObjectId tree = cache.writeTree(inserter);
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(tree);
            commit.setParentId(composite);
            PersonIdent ident = new PersonIdent("tamper", "tamper@example.com");
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("tampered: stray graft");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            return id.name();
        }
    }
}

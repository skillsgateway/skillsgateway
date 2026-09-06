package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.approval.ClosureIncompleteException;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.junit.jupiter.api.Test;

/**
 * The closure-completeness gate (GW_0164). Nothing in the gateway can produce an incomplete
 * closure, so every refusal below is arranged by hand: rows removed or rewritten through SQL, and
 * a commit synthesised in quarantine that the closure does not describe. The gate exists so that
 * nothing ever can produce one — including the code that has not been written yet — and these
 * are the proof that it would notice.
 */
class ClosureCompletenessTests extends AbstractExternalSourceTest {

    private static final String REFUSED = "snapshot-approval-refused";

    @Test
    @SVCs({"SVC_GW_0164"})
    void a_member_removed_from_the_closure_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        deleteMembers(composite.snapshot().id());

        assertRefused(composite, "tools");
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void a_composite_with_no_closure_at_all_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        jdbc.sql("DELETE FROM snapshot_closures WHERE snapshot_id = :id")
                .param("id", composite.snapshot().id())
                .update();

        assertRefused(composite, "tools");
    }

    @Test
    @SVCs({"SVC_GW_0164"})
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
    @SVCs({"SVC_GW_0164"})
    void a_member_re_pathed_away_from_its_graft_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        updateMembers(composite.snapshot().id(), "graft_path", "_plugins/elsewhere");

        assertRefused(composite, "_plugins/elsewhere");
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void a_member_whose_resolved_commit_is_not_an_object_id_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        updateMembers(composite.snapshot().id(), "resolved_sha", "not-a-commit");

        assertRefused(composite, "not-a-commit");
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void a_snapshot_re_pointed_at_a_commit_without_the_grafts_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        // The composite's parent: same marketplace, no _plugins tree, the manifest still external.
        repoint(composite.snapshot().id(), composite.upstreamHead());

        assertRefused(composite, "_plugins/tools");
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void a_stray_entry_under_the_reserved_directory_refuses_the_approval() throws Exception {
        Composite composite = ingestComposite("gate");
        String withStray = commitWithStrayGraft(
                composite.marketplace(), composite.snapshot().sha());
        repoint(composite.snapshot().id(), withStray);

        assertRefused(composite, "_plugins/stray");
    }

    @Test
    @SVCs({"SVC_GW_0164"})
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
    @SVCs({"SVC_GW_0164"})
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
    @SVCs({"SVC_GW_0164"})
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
        try (Repository quarantine = storage.quarantine(marketplace);
                ObjectReader reader = quarantine.newObjectReader();
                ObjectInserter inserter = quarantine.newObjectInserter()) {
            RevCommit composite = commit(marketplace, compositeSha);
            DirCache cache = DirCache.newInCore();
            DirCacheBuilder builder = cache.builder();
            builder.addTree(new byte[0], DirCacheEntry.STAGE_0, reader, composite.getTree());
            builder.finish();
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, "stray\n".getBytes(StandardCharsets.UTF_8));
            DirCacheEditor editor = cache.editor();
            editor.add(new DirCacheEditor.PathEdit("_plugins/stray/README.md") {
                @Override
                public void apply(DirCacheEntry entry) {
                    entry.setFileMode(FileMode.REGULAR_FILE);
                    entry.setObjectId(blob);
                }
            });
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

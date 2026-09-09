package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.approval.PublicationReconciler;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.util.Set;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The reconciliation {@code ApprovalService.repair}'s javadoc used to claim existed
 * (GW_APPROVAL_0014).
 *
 * <p>The condition it repairs is a double failure — publication fails and the compensating undo
 * fails too — which no test can reach through the public API, because reaching it means breaking
 * two things at once inside one call. So the state is arranged directly instead: a served reference
 * is removed behind the gateway's back, which is indistinguishable, to everything downstream, from
 * the row the double failure leaves behind.
 */
class PublicationReconciliationTests extends AbstractGatewayTest {

    @Autowired
    private PublicationReconciler reconciler;

    @Autowired
    private GitStorage storage;

    @Test
    @SVCs({"SVC_GW_APPROVAL_0014"})
    void an_approved_snapshot_that_is_not_served_is_republished_and_the_repair_is_on_the_ledger() throws Exception {
        Registered fixture = registerAndIngest(uniqueName("recon"), createUpstream(DEFAULT_MANIFEST));
        Snapshot approved = approve(fixture.snapshot().id());
        String marketplace = fixture.marketplace().name();
        assertThat(servedShas(marketplace)).contains(approved.sha());

        // The state the double failure leaves: the row says approved, the ref is gone.
        deleteServedRef(marketplace, approved.sha());
        assertThat(servedShas(marketplace)).doesNotContain(approved.sha());

        PublicationReconciler.Reconciliation pass = reconciler.reconcile("test");

        assertThat(servedShas(marketplace))
                .as("the approval the database records is served again")
                .contains(approved.sha());
        assertThat(pass.repaired()).contains(marketplace + "/" + approved.sha());
        assertThat(fetchLogRepository.list()).anySatisfy(entry -> {
            assertThat(entry.get("event")).isEqualTo("publication-repaired");
            assertThat(entry.get("principal")).isEqualTo(PublicationReconciler.ACTOR);
            assertThat(entry.get("marketplace")).isEqualTo(marketplace);
        });
    }

    /**
     * The other direction, which is the graver condition and is deliberately not repaired: storage
     * is serving a snapshot the database no longer calls approved. Retracting it on the strength of
     * a database comparison is what must not happen automatically.
     */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0014"})
    void content_served_without_an_approval_is_reported_and_left_exactly_where_it_is() throws Exception {
        Registered fixture = registerAndIngest(uniqueName("reconserved"), createUpstream(DEFAULT_MANIFEST));
        Snapshot approved = approve(fixture.snapshot().id());
        String marketplace = fixture.marketplace().name();

        // The row stops saying approved; the refs are untouched. This is what a revocation whose
        // unpublish failed leaves behind.
        snapshotRepository.revoke(approved.id(), "alice", "arranged for the reconciliation test");
        assertThat(servedShas(marketplace)).contains(approved.sha());

        PublicationReconciler.Reconciliation pass = reconciler.reconcile("test");

        assertThat(servedShas(marketplace))
                .as("reported, not retracted: the refs are exactly as they were")
                .contains(approved.sha());
        assertThat(pass.servedNotApproved()).contains(marketplace + "/" + approved.sha());
        assertThat(pass.repaired()).noneMatch(entry -> entry.startsWith(marketplace + "/"));
        assertThat(fetchLogRepository.list()).anySatisfy(entry -> {
            assertThat(entry.get("event")).isEqualTo("publication-served-not-approved");
            assertThat(entry.get("principal")).isEqualTo(PublicationReconciler.ACTOR);
        });
    }

    /**
     * The same double failure on a marketplace's <em>first</em> publication, where nothing is served
     * at all. Serving nothing is a known state and must reach the repair; only an unreadable
     * storage may be skipped, and the two are easy to conflate — this pins that they are not.
     */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0014"})
    void a_marketplace_serving_nothing_with_an_approved_row_is_repaired_rather_than_skipped() throws Exception {
        Registered fixture = registerAndIngest(uniqueName("reconfirst"), createUpstream(DEFAULT_MANIFEST));
        Snapshot approved = approve(fixture.snapshot().id());
        String marketplace = fixture.marketplace().name();

        deleteRef(marketplace, GitStorage.SNAPSHOT_REF_PREFIX + approved.sha());
        deleteRef(marketplace, GitStorage.SERVED_REF);
        assertThat(storage.publishedIfServing(marketplace))
                .as("nothing served at all, as after a failed first publication")
                .isEmpty();

        PublicationReconciler.Reconciliation pass = reconciler.reconcile("test");

        assertThat(pass.repaired()).contains(marketplace + "/" + approved.sha());
        assertThat(servedShas(marketplace)).contains(approved.sha());
        assertThat(storage.publishedIfServing(marketplace)).isPresent();
    }

    private Set<String> servedShas(String marketplace) throws Exception {
        try (Repository published = storage.published(marketplace)) {
            return published.getRefDatabase().getRefsByPrefix(GitStorage.SNAPSHOT_REF_PREFIX).stream()
                    .map(ref -> ref.getName().substring(GitStorage.SNAPSHOT_REF_PREFIX.length()))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    /** Behind the gateway's back, on purpose: no supported operation produces this state. */
    private void deleteServedRef(String marketplace, String sha) throws Exception {
        deleteRef(marketplace, GitStorage.SNAPSHOT_REF_PREFIX + sha);
    }

    private void deleteRef(String marketplace, String name) throws Exception {
        try (Repository published = storage.published(marketplace)) {
            Ref ref = published.exactRef(name);
            assertThat(ref).as("%s exists before it is removed", name).isNotNull();
            RefUpdate update = published.updateRef(name);
            update.setForceUpdate(true);
            assertThat(update.delete()).isEqualTo(RefUpdate.Result.FORCED);
        }
    }
}

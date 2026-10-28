package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.approval.PublicationReconciler;
import dev.skillsgateway.server.approval.RevocationService;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * An administrator's choice to serve nothing outlives a restart (GW_APPROVAL_0033): the reconciler
 * must not read "approved but not served" as a failed publication when a person chose it, and a
 * later approval returns the marketplace to service as usual.
 */
class ServeNothingPersistsTests extends AbstractGatewayTest {

    @Autowired
    private RevocationService revocationService;

    @Autowired
    private PublicationReconciler reconciler;

    @Autowired
    private GitStorage storage;

    @Test
    @SVCs({"SVC_GW_APPROVAL_0034"})
    void the_reconciler_does_not_re_serve_a_predecessor_after_a_withdrawal_that_chose_to_serve_nothing()
            throws Exception {
        String name = uniqueName("servenothing");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered first = registerAndIngest(name, upstream);
        approve(first.snapshot().id());
        addUpstreamCommit(upstream, "second");
        Snapshot second = ingestionService.ingest(first.marketplace(), null);
        approve(second.id());

        revocationService.revoke(second.id(), "compromised", RevocationService.ServeAfter.NOTHING, "root");
        assertThat(storage.publishedIfServing(name))
                .as("served after choosing nothing")
                .isEmpty();

        reconciler.reconcile("startup");

        assertThat(storage.publishedIfServing(name))
                .as("re-served by the reconciler")
                .isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0034"})
    void a_later_approval_returns_the_marketplace_to_service_and_the_reconciler_keeps_it() throws Exception {
        String name = uniqueName("servenothingback");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered first = registerAndIngest(name, upstream);
        approve(first.snapshot().id());
        revocationService.revoke(first.snapshot().id(), "compromised", RevocationService.ServeAfter.NOTHING, "root");

        addUpstreamCommit(upstream, "fixed");
        Snapshot fixed = ingestionService.ingest(first.marketplace(), null);
        approve(fixed.id());
        // A lost publication of the new approval is still the reconciler's to repair.
        storage.unpublish(name, fixed.sha());

        reconciler.reconcile("startup");

        assertThat(storage.publishedIfServing(name))
                .as("the new approval was not repaired")
                .isPresent();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0034"})
    void a_withdrawal_that_returned_to_the_predecessor_leaves_it_repairable() throws Exception {
        String name = uniqueName("servenothingrollback");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered first = registerAndIngest(name, upstream);
        approve(first.snapshot().id());
        addUpstreamCommit(upstream, "second");
        Snapshot second = ingestionService.ingest(first.marketplace(), null);
        approve(second.id());
        revocationService.revoke(second.id(), "compromised", RevocationService.ServeAfter.PREVIOUS_APPROVED, "root");
        // The predecessor is meant to be served; a lost publication of it is still a repair.
        storage.unpublish(name, first.snapshot().sha());

        reconciler.reconcile("startup");

        assertThat(storage.publishedIfServing(name))
                .as("the returned predecessor was not repaired")
                .isPresent();
    }
}

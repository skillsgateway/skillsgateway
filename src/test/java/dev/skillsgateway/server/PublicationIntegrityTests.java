package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.approval.ApprovalException;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Whether a publication the gateway reports actually happened.
 *
 * <p>The approval gate's whole guarantee is that the served ref is the approved SHA. A ref update
 * that is refused rather than thrown — {@code LOCK_FAILURE} is the ordinary one — must not be able
 * to produce an approval, a ledger entry or an event that says otherwise.
 */
class PublicationIntegrityTests extends AbstractGatewayTest {

    @Autowired
    private GitStorage storage;

    /** Holds {@code refs/heads/main} the way a competing writer would, so the update is refused. */
    private static Path lockServedRef(Repository published) throws Exception {
        Path lock = published.getDirectory().toPath().resolve("refs/heads/main.lock");
        Files.createDirectories(lock.getParent());
        Files.writeString(lock, "");
        return lock;
    }

    @Test
    @SVCs({"SVC_GW_0133"})
    void a_refused_publication_does_not_report_an_approval() throws Exception {
        String name = uniqueName("corp");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(name, upstream);
        Snapshot served = approve(registered.snapshot().id());

        // A second snapshot, so main already exists and a refusal leaves the estate mid-flight
        // rather than simply unserved.
        addUpstreamCommit(upstream, "second");
        Snapshot next = ingestionService.ingest(registered.marketplace(), null);
        assertThat(next.state()).isEqualTo(Snapshot.HELD);

        Path lock;
        try (Repository published = storage.published(name)) {
            lock = lockServedRef(published);
        }
        try {
            // Through the controller, because the ledger entry and the lifecycle event are written by
            // the caller once approve returns: the point is that neither is reached.
            assertThatThrownBy(() -> mockMvc.perform(post("/api/snapshots/%d/approve".formatted(next.id()))
                            .with(oidcLogin())))
                    .as("a publication that did not happen fails loudly")
                    .hasCauseInstanceOf(ApprovalException.class)
                    .rootCause()
                    .as("and it fails because the refusal was detected, not for some other reason")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("refs/heads/main")
                    .hasMessageContaining("LOCK_FAILURE");
        } finally {
            Files.deleteIfExists(lock);
        }

        assertThat(snapshotRepository.findById(next.id()).orElseThrow().state())
                .as("a snapshot whose publication was refused is not approved")
                .isEqualTo(Snapshot.HELD);

        String audit = mockMvc.perform(get("/api/audit").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> approvals =
                JsonPath.read(audit, "$[?(@.event == 'snapshot-approved' && @.sha == '%s')].sha".formatted(next.sha()));
        assertThat(approvals)
                .as("the ledger must not record a publication that did not happen")
                .isEmpty();

        String url = facadeUrl(name, newPat());
        GitResult lsRemote = git(null, "ls-remote", url);
        assertThat(lsRemote.exitCode()).as(lsRemote.output()).isZero();
        assertThat(lsRemote.output())
                .as("the served tip did not move")
                .contains(served.sha())
                .as("and the refused snapshot is not on the wire at all, by name or otherwise")
                .doesNotContain(next.sha());
    }

    @Test
    @SVCs({"SVC_GW_0133"})
    void a_refused_first_publication_leaves_the_marketplace_unserved() throws Exception {
        String name = uniqueName("corp");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));

        // The published repository has to exist for its served ref to be lockable, which is also
        // the state a marketplace is in before its first approval.
        Path lock;
        try (Repository published = storage.published(name)) {
            lock = lockServedRef(published);
        }
        try {
            assertThatThrownBy(
                            () -> approvalService.approve(registered.snapshot().id(), "alice"))
                    .isInstanceOf(ApprovalException.class);
        } finally {
            Files.deleteIfExists(lock);
        }

        assertThat(snapshotRepository
                        .findById(registered.snapshot().id())
                        .orElseThrow()
                        .state())
                .isEqualTo(Snapshot.HELD);
        assertThat(storage.publishedIfServing(name))
                .as("a marketplace whose first publication was refused serves nothing at all")
                .isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_0133"})
    void a_refused_republication_of_a_revoked_snapshot_keeps_its_revocation() throws Exception {
        String name = uniqueName("corp");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();
        approve(id);
        Snapshot revoked = snapshotRepository
                .revoke(id, "revet", "a violation its waivers do not cover")
                .orElseThrow();
        assertThat(revoked.state()).isEqualTo(Snapshot.REVOKED);
        assertThat(revoked.revokedBy()).isEqualTo("revet");

        Path lock;
        try (Repository published = storage.published(name)) {
            lock = lockServedRef(published);
        }
        try {
            assertThatThrownBy(() -> approvalService.approve(id, "alice")).isInstanceOf(ApprovalException.class);
        } finally {
            Files.deleteIfExists(lock);
        }

        // decide() clears revoked_at, revoked_by and violation on its way to approved. If the
        // publication then fails, a row left as decide() wrote it would have lost a revocation the
        // ledger still records.
        Snapshot after = snapshotRepository.findById(id).orElseThrow();
        assertThat(after.state()).isEqualTo(Snapshot.REVOKED);
        assertThat(after.revokedBy()).isEqualTo(revoked.revokedBy());
        assertThat(after.revokedAt()).isEqualTo(revoked.revokedAt());
        assertThat(after.violation()).isEqualTo(revoked.violation());
    }
}

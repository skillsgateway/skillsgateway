package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
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
            mockMvc.perform(post("/api/snapshots/%d/approve".formatted(next.id())).with(oidcLogin()))
                    .andExpect(status().is5xxServerError());
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
        List<String> approvals = JsonPath.read(
                audit, "$[?(@.event == 'snapshot-approved' && @.sha == '%s')].sha".formatted(next.sha()));
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
}

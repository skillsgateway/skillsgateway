package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.retention.RetentionService;
import dev.skillsgateway.server.storage.GitStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the other reference transitions do when they are refused.
 *
 * <p>Each of these used to discard its {@code RefUpdate.Result}, and each failed differently.
 * Retention's is the worst: the purge order is remove-the-pin, delete the row, write the ledger
 * entry, so a refused deletion left the pin while the row went away — and because the row was gone,
 * nothing would ever revisit it. The content would be retained forever, garbage collection would
 * reclaim nothing, and the ledger would assert a deletion that never happened.
 */
class RefRefusalTests extends AbstractGatewayTest {

    @Autowired
    private GitStorage storage;

    @Autowired
    private RetentionService retentionService;

    /** Holds a reference the way a competing writer would, so the transition is refused. */
    private boolean hasPin(String marketplace, String sha) throws Exception {
        try (Repository quarantine = storage.quarantine(marketplace)) {
            return quarantine.exactRef("refs/snapshots/" + sha) != null;
        }
    }

    private static Path lock(Repository repository, String ref) throws Exception {
        Path lockFile = repository.getDirectory().toPath().resolve(ref + ".lock");
        Files.createDirectories(lockFile.getParent());
        Files.writeString(lockFile, "held by another writer\n");
        return lockFile;
    }

    @Test
    void a_purge_that_cannot_remove_the_pin_keeps_the_row_and_says_nothing() throws Exception {
        String name = uniqueName("retain");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        Snapshot doomed = registered.snapshot();
        snapshotRepository.softDelete(
                doomed.id(), "held-too-long", Instant.now().minus(1, ChronoUnit.MINUTES));

        Path lockFile;
        try (Repository quarantine = storage.quarantine(name)) {
            lockFile = lock(quarantine, "refs/snapshots/" + doomed.sha());
        }
        RetentionService.PassResult pass;
        try {
            // compact() already had the right answer for a failed pin removal -- log it, leave the
            // row, let the next pass retry -- but the discarded result meant delete() never threw,
            // so that path was unreachable. Checking the result is what makes it live.
            pass = retentionService.compact("alice");
        } finally {
            Files.deleteIfExists(lockFile);
        }

        assertThat(pass.acted())
                .as("a snapshot whose pin could not be removed is not counted as purged")
                .isZero();
        assertThat(hasPin(name, doomed.sha()))
                .as("the pin is still there, which is why the row must be too")
                .isTrue();
        assertThat(snapshotRepository.findById(doomed.id()))
                .as("the row survives, so a later pass will try again rather than losing the snapshot"
                        + " to a retention promise nothing kept")
                .isPresent();

        String audit = mockMvc.perform(get("/api/audit").with(oidcLogin()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> purges =
                JsonPath.read(audit, "$[?(@.event == 'snapshot-purged' && @.sha == '%s')].sha".formatted(doomed.sha()));
        assertThat(purges)
                .as("the ledger must not assert a deletion that did not happen")
                .isEmpty();
    }

    @Test
    void an_ingestion_that_cannot_pin_the_snapshot_fails() throws Exception {
        String name = uniqueName("pin");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(name, upstream);
        String nextSha = addUpstreamCommit(upstream, "second");

        Path lockFile;
        try (Repository quarantine = storage.quarantine(name)) {
            lockFile = lock(quarantine, "refs/snapshots/" + nextSha);
        }
        try {
            assertThatThrownBy(() -> ingestionService.ingest(registered.marketplace(), null))
                    .as("a snapshot that could not be pinned must not become a reviewable row")
                    .isNotNull();
        } finally {
            Files.deleteIfExists(lockFile);
        }

        assertThat(snapshotRepository.findByMarketplaceAndSha(
                        registered.marketplace().id(), nextSha))
                .as("nothing is approvable that publication would later fail to find")
                .isEmpty();
    }
}

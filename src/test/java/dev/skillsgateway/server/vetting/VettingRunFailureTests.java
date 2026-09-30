package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.webhook.WebhookService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * What the chain records when it cannot finish, without a Spring context: only a failure to read
 * the snapshot's content is a {@code snapshot-access} verdict. Anything else — the database going
 * away while a verdict is recorded — is not a fact about the snapshot and is not written as one.
 */
class VettingRunFailureTests {

    private static final long RUN = 7L;

    private final VettingRepository runs = mock(VettingRepository.class);
    private final GitStorage storage = mock(GitStorage.class);
    private final VettingService service = service();

    @TempDir
    Path work;

    @Test
    void a_snapshot_whose_content_cannot_be_opened_is_a_snapshot_access_error() throws Exception {
        given(storage.quarantine(anyString())).willThrow(new IOException("object store unreachable"));

        VettingService.Run run = service.run(snapshot("0".repeat(40)), "m", VettingRepository.TRIGGER_INGESTION);

        ArgumentCaptor<Verdict> verdict = ArgumentCaptor.forClass(Verdict.class);
        verify(runs).recordVerdict(eq(RUN), eq("snapshot-access"), eq(0), verdict.capture());
        assertThat(verdict.getValue().state()).isEqualTo(VerdictState.ERROR);
        assertThat(run.outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);
    }

    @Test
    void a_database_failure_propagates_and_is_not_recorded_as_snapshot_access() throws Exception {
        String sha = commit();
        given(storage.quarantine(anyString()))
                .willAnswer(call -> Git.open(work.toFile()).getRepository());
        willThrow(new DataAccessResourceFailureException("connection lost"))
                .given(runs)
                .recordVerdict(eq(RUN), eq("probe"), anyInt(), any());

        assertThatThrownBy(() -> service.run(snapshot(sha), "m", VettingRepository.TRIGGER_INGESTION))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(runs, never()).recordVerdict(anyLong(), eq("snapshot-access"), anyInt(), any());
        verify(runs, never()).finishRun(anyLong(), any());
    }

    private String commit() throws Exception {
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            Repository repository = git.getRepository();
            git.commit()
                    .setMessage("content")
                    .setAuthor("t", "t@example.com")
                    .setCommitter("t", "t@example.com")
                    .setAllowEmpty(true)
                    .setSign(false)
                    .call();
            return repository.resolve("HEAD").name();
        }
    }

    private VettingService service() {
        given(runs.startRun(anyLong(), anyString(), anyString())).willReturn(RUN);
        VetterToggleService toggles = mock(VetterToggleService.class);
        given(toggles.enabled(anyString(), anyLong())).willReturn(true);
        VettingChainSettingsService settings = mock(VettingChainSettingsService.class);
        given(settings.resolveOrder(anyLong()))
                .willReturn(new VettingChainSettingsService.OrderResolution(null, null, null));
        given(settings.resolveMode(anyLong()))
                .willReturn(new VettingChainSettingsService.ModeResolution(ChainMode.RUN_ALL, null, null));
        SkillsGatewayProperties properties = mock(SkillsGatewayProperties.class);
        given(properties.vetting())
                .willReturn(new SkillsGatewayProperties.Vetting(null, null, null, null, null, null, null, null, null));
        return new VettingService(
                List.of(new Probe()),
                runs,
                storage,
                mock(AdminAuditLogger.class),
                mock(WebhookService.class),
                mock(WaiverService.class),
                toggles,
                settings,
                properties);
    }

    private static Snapshot snapshot(String sha) {
        return new Snapshot(
                1L,
                2L,
                sha,
                sha,
                Snapshot.APPROVED,
                null,
                Instant.now(),
                "root",
                "root",
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** A vetter that always passes, so the only failure in play is the one each test injects. */
    private static final class Probe implements Vetter {
        @Override
        public String name() {
            return "probe";
        }

        @Override
        public int order() {
            return 0;
        }

        @Override
        public String description() {
            return "passes";
        }

        @Override
        public Verdict vet(SnapshotUnderVetting snapshot) {
            return Verdict.pass();
        }
    }
}

package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;

class FacadeTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0006"})
    void standardGitClientClonesApprovedSnapshot() throws Exception {
        String name = uniqueName("corp");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(name, upstream);
        approve(registered.snapshot().id());

        Path dest = newWorkDir("clone");
        GitResult result = gitClone(facadeUrl(name, newPat()), dest);

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(headSha(dest)).isEqualTo(registered.snapshot().sha());
        assertThat(Files.readString(dest.resolve(MANIFEST_PATH)))
                .isEqualTo(Files.readString(upstream.resolve(MANIFEST_PATH)));
    }

    @Test
    @SVCs({"SVC_GW_0004"})
    void upstreamChangesNeverAlterServedContent() throws Exception {
        String name = uniqueName("corp");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(name, upstream);
        Snapshot approved = approve(registered.snapshot().id());

        String newSha = addUpstreamCommit(upstream, "post-approval-change");
        Snapshot held = ingestionService.ingest(registered.marketplace());

        assertThat(held.state()).isEqualTo(Snapshot.HELD);
        assertThat(held.sha()).isEqualTo(newSha).isNotEqualTo(approved.sha());

        Path dest = newWorkDir("clone");
        GitResult result = gitClone(facadeUrl(name, newPat()), dest);
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(headSha(dest)).isEqualTo(approved.sha());
    }

    @Test
    @SVCs({"SVC_GW_0007"})
    void heldContentIsNotAdvertisedAndCannotBeFetched() throws Exception {
        String name = uniqueName("corp");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(name, upstream);
        Snapshot approved = approve(registered.snapshot().id());
        String heldSha = addUpstreamCommit(upstream, "held-only-change");
        Snapshot held = ingestionService.ingest(registered.marketplace());
        assertThat(held.sha()).isEqualTo(heldSha);

        String url = facadeUrl(name, newPat());
        GitResult lsRemote = git(null, "ls-remote", url);
        assertThat(lsRemote.exitCode()).as(lsRemote.output()).isZero();
        assertThat(lsRemote.output()).contains(approved.sha()).doesNotContain(heldSha);

        Path fresh = newWorkDir("fetch");
        Git.init().setDirectory(fresh.toFile()).setInitialBranch("main").call().close();
        GitResult fetch = git(fresh, "fetch", url, heldSha);
        assertThat(fetch.exitCode()).as(fetch.output()).isNotZero();
    }

    @Test
    @SVCs({"SVC_GW_0008"})
    void facadeFetchesAreAuditLogged() throws Exception {
        String name = uniqueName("corp");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        Snapshot approved = approve(registered.snapshot().id());

        GitResult result = gitClone(facadeUrl(name, newPat()), newWorkDir("clone"));
        assertThat(result.exitCode()).as(result.output()).isZero();

        List<Map<String, Object>> events = fetchLogRepository.list().stream()
                .filter(row -> name.equals(row.get("marketplace")))
                .toList();
        assertThat(events).extracting(row -> row.get("event")).contains("info-refs", "upload-pack");
        Map<String, Object> uploadPack = events.stream()
                .filter(row -> "upload-pack".equals(row.get("event")))
                .findFirst()
                .orElseThrow();
        assertThat(uploadPack.get("sha")).isEqualTo(approved.sha());
        assertThat(uploadPack.get("ref")).isEqualTo("refs/heads/main");
        assertThat(uploadPack.get("principal")).isEqualTo("alice");
        assertThat((String) uploadPack.get("source")).isNotBlank();
        assertThat(uploadPack.get("ts")).isNotNull();
    }
}

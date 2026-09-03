package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;

/**
 * What the fetch ledger says a client asked for (GW_0154), driven through the real git binary
 * against the real facade.
 *
 * <p>The facade advertises two namespaces, and both are legal wants: the served tip, and every
 * approved snapshot by name — including one a later approval has superseded. The ledger has to
 * distinguish them, because "give me whatever you serve now" and "give me this specific snapshot"
 * are different requests and only the second can be about content the marketplace no longer serves.
 */
class FetchLedgerRefTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0154"})
    void a_fetch_of_a_superseded_snapshot_records_that_snapshots_ref() throws Exception {
        String name = uniqueName("corp");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(name, upstream);
        Snapshot superseded = approve(registered.snapshot().id());

        // A second approval moves refs/heads/main off the first snapshot, which stays advertised as
        // refs/snapshots/<sha> in its own right. Only now are the two refs distinct object ids, so
        // only now can a want name one of them unambiguously.
        addUpstreamCommit(upstream, "supersede");
        Snapshot current =
                approve(ingestionService.ingest(registered.marketplace(), null).id());
        assertThat(current.sha()).isNotEqualTo(superseded.sha());

        String url = facadeUrl(name, newPat());
        Path fresh = newWorkDir("fetch-superseded");
        Git.init().setDirectory(fresh.toFile()).setInitialBranch("main").call().close();
        GitResult fetch = git(fresh, "fetch", url, superseded.sha());
        assertThat(fetch.exitCode()).as(fetch.output()).isZero();

        Map<String, Object> entry = uploadPackEntryFor(name, superseded.sha());
        assertThat(entry.get("ref"))
                .as("the ledger names the advertised ref the want resolved to, not the served tip")
                .isEqualTo("refs/snapshots/" + superseded.sha());
    }

    @Test
    @SVCs({"SVC_GW_0154"})
    void a_clone_of_the_served_tip_records_the_tip() throws Exception {
        String name = uniqueName("corp");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        Snapshot approved = approve(registered.snapshot().id());

        GitResult clone = gitClone(facadeUrl(name, newPat()), newWorkDir("clone"));
        assertThat(clone.exitCode()).as(clone.output()).isZero();

        // refs/heads/main and refs/snapshots/<sha> are the same commit while the snapshot is
        // current, and the protocol puts only object ids in the want list, so the two are not
        // separable here by any implementation. The tip wins, deterministically.
        Map<String, Object> entry = uploadPackEntryFor(name, approved.sha());
        assertThat(entry.get("ref"))
                .as("a want equal to the served tip records the tip, even though the snapshot ref matches too")
                .isEqualTo("refs/heads/main");
    }

    private Map<String, Object> uploadPackEntryFor(String marketplace, String sha) {
        List<Map<String, Object>> entries = fetchLogRepository.list().stream()
                .filter(row -> marketplace.equals(row.get("marketplace")))
                .filter(row -> "upload-pack".equals(row.get("event")))
                .filter(row -> sha.equals(row.get("sha")))
                .toList();
        assertThat(entries).as("a pack transfer of %s is on the ledger", sha).isNotEmpty();
        return entries.getFirst();
    }
}

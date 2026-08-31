package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.catalog.CatalogService;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * What the fetch facade puts on the wire, asserted as a stated set rather than as a consequence of
 * whatever a repository happens to hold.
 *
 * <p>The two tests here have different jobs. The snapshot-ref test is a characterization: fetching
 * an approved snapshot by name is deliberate, documented behaviour, and the test exists so an
 * advertisement allowlist cannot silently over-restrict. The catalog-ref test is the exposure: the
 * catalog repository is served by the ordinary facade, its internal scaffolding refs live in it, and
 * nothing but a discarded prune result keeps them off the wire.
 */
@TestPropertySource(properties = {"skills-gateway.catalog.name=catalog"})
class RefAdvertisementTests extends AbstractGatewayTest {

    @Autowired
    private GitStorage storage;

    @Autowired
    private CatalogService catalogService;

    @Test
    void an_approved_snapshot_stays_fetchable_by_name() throws Exception {
        String name = uniqueName("corp");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        Snapshot approved = approve(registered.snapshot().id());

        String url = facadeUrl(name, newPat());
        GitResult lsRemote = git(null, "ls-remote", url);

        assertThat(lsRemote.exitCode()).as(lsRemote.output()).isZero();
        assertThat(lsRemote.output())
                .as("the pinned snapshot ref is advertised in its own right")
                .contains("refs/snapshots/" + approved.sha());

        Path fresh = newWorkDir("fetch-by-name");
        Git.init().setDirectory(fresh.toFile()).setInitialBranch("main").call().close();
        GitResult fetch = git(fresh, "fetch", url, approved.sha());
        assertThat(fetch.exitCode()).as(fetch.output()).isZero();

        // The advertised set is stated, not sampled: anything new appearing here is a decision
        // someone has to make on purpose.
        assertThat(lsRemote.output().lines().map(line -> line.split("\\s+")[1]).toList())
                .as("the whole advertised surface")
                .allSatisfy(ref -> assertThat(ref)
                        .satisfiesAnyOf(
                                advertised -> assertThat(advertised).isEqualTo("HEAD"),
                                advertised -> assertThat(advertised).isEqualTo("refs/heads/main"),
                                advertised -> assertThat(advertised).startsWith("refs/snapshots/")));
    }

    @Test
    void no_internal_catalog_ref_is_advertised() throws Exception {
        String name = uniqueName("corp");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());
        catalogService.rebuild();

        // A prune whose result was refused leaves exactly this residue behind. Written directly so
        // the assertion is about what the facade advertises, not about how the ref came to exist.
        String leftover = "refs/catalog/" + name;
        try (Repository catalog = storage.published("catalog")) {
            ObjectId tip = catalog.resolve("refs/heads/main");
            assertThat(tip)
                    .as("the catalog serves a tip to hang the residue off")
                    .isNotNull();
            RefUpdate residue = catalog.updateRef(leftover);
            residue.setNewObjectId(tip);
            residue.setForceUpdate(true);
            assertThat(residue.forceUpdate()).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED);
        }

        GitResult lsRemote = git(null, "ls-remote", facadeUrl("catalog", newPat()));

        assertThat(lsRemote.exitCode()).as(lsRemote.output()).isZero();
        assertThat(lsRemote.output())
                .as("only the served tip belongs on the wire; scaffolding refs do not")
                .doesNotContain(leftover);
    }
}

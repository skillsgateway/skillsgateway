package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.catalog.CatalogService;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.ingestion.ExternalSourceResolver;
import dev.skillsgateway.server.ingestion.IngestionService;
import dev.skillsgateway.server.ingestion.ManifestPolicy;
import dev.skillsgateway.server.ingestion.ManifestRewriter;
import dev.skillsgateway.server.ingestion.UpstreamGit;
import dev.skillsgateway.server.observability.GatewayMetrics;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.policy.SnapshotFactsService;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreTestSupport;
import dev.skillsgateway.server.vetting.VettingService;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The two services that copy objects between repositories the gateway holds open, on both backends.
 *
 * <p>Both used to fetch by {@code getDirectory().getAbsolutePath()}, which is null for an
 * object-store repository, so on that backend the catalog never rebuilt and a hosted marketplace
 * never ingested. Each service is constructed over the backend under test with the context's own
 * collaborators; only the vetting chain and the facts recorder are stubbed, because they read the
 * context's filesystem storage and are not what these cases are about.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorageSeamServicesTests extends AbstractGatewayTest {

    private static final String MAIN = Constants.R_HEADS + "main";

    @Autowired
    private GitStorage storage;

    @Autowired
    private SkillsGatewayProperties properties;

    @Autowired
    private AdminAuditLogger auditLogger;

    @Autowired
    private GatewayMetrics metrics;

    @Autowired
    private ManifestPolicy manifestPolicy;

    @Autowired
    private ExternalSourceResolver externalSourceResolver;

    @Autowired
    private ManifestRewriter manifestRewriter;

    @Autowired
    private UpstreamGit upstreamGit;

    private GitStorage objectStoreStorage;

    private record Backend(String name, GitStorage storage) {
        @Override
        public String toString() {
            return name;
        }
    }

    private List<Backend> backends() {
        return List.of(new Backend("filesystem", storage), new Backend("object-store", objectStore()));
    }

    private synchronized GitStorage objectStore() {
        if (objectStoreStorage == null) {
            try {
                objectStoreStorage = ObjectStoreTestSupport.storage(
                        ObjectStoreTestSupport.client(), ObjectStoreTestSupport.isolatedPrefix("storage-seam"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return objectStoreStorage;
    }

    // the catalog rebuilds from served content on either backend
    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_FACADE_0003"})
    void theCatalogVendorsAServedMarketplaceOnEitherBackend(Backend backend) throws Exception {
        String name = uniqueName("seam-catalog");
        marketplaceRepository.register(name, "https://example.invalid/" + name + ".git");
        ObjectId tip;
        try (Repository published = backend.storage().published(name)) {
            tip = commitMarketplace(published);
            setRef(published, MAIN, tip);
            setRef(published, "refs/snapshots/" + tip.name(), tip);
        }
        CatalogService catalogService =
                new CatalogService(backend.storage(), marketplaceRepository, properties, auditLogger, metrics);

        CatalogService.CatalogInfo info = catalogService.rebuild();

        assertThat(info.constituents()).contains(new CatalogService.Constituent(name, tip.name()));
        try (Repository catalog =
                        backend.storage().published(properties.catalog().name());
                RevWalk walk = new RevWalk(catalog)) {
            ObjectId catalogTip = catalog.resolve(MAIN);
            assertThat(catalogTip).as("the catalog's main moved").isNotNull();
            var tree = walk.parseCommit(catalogTip).getTree();
            try (TreeWalk skill = TreeWalk.forPath(catalog, name + "/plugins/hello/skills/hello/SKILL.md", tree)) {
                assertThat(skill)
                        .as("the vendored subtree is readable from the catalog")
                        .isNotNull();
                assertThat(new String(catalog.open(skill.getObjectId(0)).getBytes(), StandardCharsets.UTF_8))
                        .isEqualTo(CONFORMANT_SKILL);
            }
            assertThat(catalog.getRefDatabase().getRefsByPrefix("refs/catalog/"))
                    .as("no scaffolding reference is left behind")
                    .isEmpty();
        }
    }

    // a hosted marketplace ingests its pushed lineage on either backend
    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_INGEST_0017"})
    void aHostedMarketplaceIngestsItsLineageOnEitherBackend(Backend backend) throws Exception {
        String name = uniqueName("seam-hosted");
        Marketplace marketplace = marketplaceRepository.register(
                name, null, null, Marketplace.ORIGIN_HOSTED, Marketplace.PUSH_APPEND_ONLY, null);
        ObjectId lineage;
        try (Repository origin = backend.storage().hosted(name)) {
            lineage = commitMarketplace(origin);
            setRef(origin, Marketplace.LINEAGE_REF, lineage);
        }
        IngestionService ingestion = new IngestionService(
                backend.storage(),
                snapshotRepository,
                mock(VettingService.class),
                manifestPolicy,
                externalSourceResolver,
                manifestRewriter,
                mock(SnapshotFactsService.class),
                metrics,
                upstreamGit,
                marketplaceRepository,
                auditLogger);

        Snapshot snapshot = ingestion.ingest(marketplace, "alice");

        assertThat(snapshot.sha()).isEqualTo(lineage.name());
        assertThat(snapshot.state()).isEqualTo(Snapshot.HELD);
        try (Repository quarantine = backend.storage().quarantine(name)) {
            assertThat(quarantine.resolve("refs/snapshots/" + lineage.name()))
                    .as("the snapshot is pinned in quarantine")
                    .isEqualTo(lineage);
            assertThat(quarantine.getObjectDatabase().has(lineage)).isTrue();
        }
    }

    /** One commit holding the default fixture: a manifest and one conformant skill. */
    private static ObjectId commitMarketplace(Repository repository) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId skill = inserter.insert(Constants.OBJ_BLOB, CONFORMANT_SKILL.getBytes(StandardCharsets.UTF_8));
            ObjectId skillDir = tree(inserter, "SKILL.md", FileMode.REGULAR_FILE, skill);
            ObjectId hello = tree(inserter, "hello", FileMode.TREE, skillDir);
            ObjectId skills = tree(inserter, "skills", FileMode.TREE, hello);
            ObjectId plugins = tree(inserter, "hello", FileMode.TREE, skills);
            ObjectId manifest = inserter.insert(Constants.OBJ_BLOB, DEFAULT_MANIFEST.getBytes(StandardCharsets.UTF_8));
            ObjectId claudePlugin = tree(inserter, "marketplace.json", FileMode.REGULAR_FILE, manifest);
            TreeFormatter root = new TreeFormatter();
            root.append(".claude-plugin", FileMode.TREE, claudePlugin);
            root.append("plugins", FileMode.TREE, plugins);
            ObjectId rootTree = inserter.insert(root);
            PersonIdent who = new PersonIdent("Seam", "seam@example.invalid", Instant.EPOCH, ZoneOffset.UTC);
            CommitBuilder builder = new CommitBuilder();
            builder.setTreeId(rootTree);
            builder.setAuthor(who);
            builder.setCommitter(who);
            builder.setMessage("publish");
            ObjectId commit = inserter.insert(builder);
            inserter.flush();
            return commit;
        }
    }

    private static ObjectId tree(ObjectInserter inserter, String name, FileMode mode, ObjectId id) throws IOException {
        TreeFormatter tree = new TreeFormatter();
        tree.append(name, mode, id);
        return inserter.insert(tree);
    }

    private static void setRef(Repository repository, String ref, ObjectId to) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setNewObjectId(to);
        RefUpdate.Result result = update.forceUpdate();
        assertThat(result)
                .as("fixture setup must not be the thing that fails")
                .isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE);
    }
}

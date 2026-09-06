package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotClosureRepository;
import dev.skillsgateway.server.storage.GitStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * A gateway with external sources enabled against the in-process forge: the arrangement every
 * closure test needs (GW_0164, GW_0165). Its own Spring context for the reason
 * {@code ExternalSourceResolutionTests} gives — the shared context keeps the shipped default.
 *
 * <p>The forge is started in a static initialiser because the context reads
 * {@code github-base-url} while it starts, and it is closed by a shutdown hook rather than an
 * {@code @AfterAll}: two suites share it, and whichever ran first must not pull it out from under
 * the other.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.ingestion.external-sources.enabled=true",
            "skills-gateway.ingestion.external-sources.allowed-types=github",
            "skills-gateway.ingestion.external-sources.allow-private-networks=true",
            "skills-gateway.ingestion.external-sources.budgets.deadline=60s"
        })
abstract class AbstractExternalSourceTest extends AbstractGatewayTest {

    protected static final GitHttpFixture FORGE = startForge();

    private static GitHttpFixture startForge() {
        try {
            GitHttpFixture forge = new GitHttpFixture();
            Runtime.getRuntime().addShutdownHook(new Thread(forge::close, "forge-shutdown"));
            return forge;
        } catch (IOException e) {
            throw new IllegalStateException("could not start the in-process forge", e);
        }
    }

    @DynamicPropertySource
    static void forgeBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("skills-gateway.ingestion.external-sources.github-base-url", FORGE::baseUrl);
    }

    @Autowired
    protected GitStorage storage;

    @Autowired
    protected SnapshotClosureRepository closureRepository;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetForge() {
        FORGE.reset();
    }

    /** A marketplace manifest declaring one local plugin and one external source. */
    protected static String manifestWithExternal(String ownerRepo) {
        return """
                {
                  "name": "test-marketplace",
                  "owner": {"name": "Test"},
                  "plugins": [
                    {"name": "hello", "source": "./plugins/hello", "description": "local"},
                    {"name": "tools", "source": {"source": "github", "repo": "%s"}, "description": "external"}
                  ]
                }
                """.formatted(ownerRepo);
    }

    /** A composite snapshot, held: one external plugin {@code tools} resolved from the forge. */
    protected record Composite(Registered registered, String upstreamHead, String cloneUrl) {

        Snapshot snapshot() {
            return registered.snapshot();
        }

        String marketplace() {
            return registered.marketplace().name();
        }
    }

    protected Composite ingestComposite(String prefix) throws Exception {
        return ingestComposite(prefix, "acme/tools");
    }

    protected Composite ingestComposite(String prefix, String ownerRepo) throws Exception {
        FORGE.publish(ownerRepo, Map.of("skills/tool/SKILL.md", "# Tool\n\nA tool.\n"));
        Path upstream = createUpstream(manifestWithExternal(ownerRepo));
        String upstreamHead = headSha(upstream);
        Registered registered = registerAndIngest(uniqueName(prefix), upstream);
        assertThat(registered.snapshot().state())
                .as(registered.snapshot().violation())
                .isEqualTo(Snapshot.HELD);
        return new Composite(registered, upstreamHead, FORGE.baseUrl() + "/" + ownerRepo);
    }

    /** The tree at {@code path} in the commit {@code sha}, or null when the path is absent. */
    protected ObjectId treeAt(String marketplace, String sha, String path) throws IOException {
        try (Repository quarantine = storage.quarantine(marketplace);
                RevWalk walk = new RevWalk(quarantine)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
            try (TreeWalk tree = TreeWalk.forPath(quarantine, path, commit.getTree())) {
                return tree == null ? null : tree.getObjectId(0);
            }
        }
    }

    protected RevCommit commit(String marketplace, String sha) throws IOException {
        try (Repository quarantine = storage.quarantine(marketplace);
                RevWalk walk = new RevWalk(quarantine)) {
            return walk.parseCommit(ObjectId.fromString(sha));
        }
    }
}

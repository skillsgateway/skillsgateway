package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.facade.FetchAuditHook;
import dev.skillsgateway.server.facade.GitFacadeConfiguration;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreTestSupport;
import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The facade serving from the object-store backend (#614).
 *
 * <p>The shared context runs the filesystem backend, so this builds the facade over a bucket-backed
 * seam with the context's ledger and marketplace table, and hosts its servlet on a Tomcat of its
 * own for a real git client to reach. Authentication is not under test here and is not wired; it
 * is the same filter chain on either backend.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObjectStoreFacadeTests extends AbstractGatewayTest {

    private static final String MAIN = Constants.R_HEADS + "main";

    @Autowired
    private FetchAuditHook auditHook;

    @Autowired
    private SkillsGatewayProperties properties;

    private GitStorage objectStore;
    private Tomcat tomcat;
    private int facadePort;

    @BeforeAll
    void startFacade() throws Exception {
        objectStore = ObjectStoreTestSupport.storage(
                ObjectStoreTestSupport.client(), ObjectStoreTestSupport.isolatedPrefix("object-store-facade"));
        GitFacadeConfiguration facade =
                new GitFacadeConfiguration(objectStore, auditHook, marketplaceRepository, properties);

        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("object-store-facade").toString());
        tomcat.setPort(0);
        Context context = tomcat.addContext("", null);
        Tomcat.addServlet(context, "git", facade.gitServlet().getServlet());
        context.addServletMappingDecoded("/git/*", "git");
        tomcat.getConnector();
        tomcat.start();
        facadePort = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    void stopFacade() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    // ls-remote and a clone are served from the object store, and the fetch is audited under its marketplace
    @Test
    @SVCs({"SVC_GW_FACADE_0001", "SVC_GW_AUDIT_0001"})
    void aStandardGitClientFetchesFromTheObjectStore() throws Exception {
        String name = uniqueName("bucket");
        marketplaceRepository.register(name, "https://example.invalid/" + name + ".git");
        ObjectId tip;
        try (Repository published = objectStore.published(name)) {
            tip = commit(published, "approved content\n");
            setRef(published, MAIN, tip);
            setRef(published, GitStorage.SNAPSHOT_REF_PREFIX + tip.name(), tip);
        }
        String url = "http://127.0.0.1:" + facadePort + "/git/" + name;

        GitResult lsRemote = git(null, "ls-remote", url);
        assertThat(lsRemote.exitCode()).as(lsRemote.output()).isZero();
        assertThat(lsRemote.output()).contains(tip.name() + "\t" + MAIN);

        Path dest = newWorkDir("object-store-clone");
        GitResult clone = gitClone(url, dest);
        assertThat(clone.exitCode()).as(clone.output()).isZero();
        assertThat(headSha(dest)).isEqualTo(tip.name());
        assertThat(Files.readString(dest.resolve("README.md"))).isEqualTo("approved content\n");

        List<Map<String, Object>> uploads = fetchLogRepository.list().stream()
                .filter(row -> name.equals(row.get("marketplace")))
                .filter(row -> "upload-pack".equals(row.get("event")))
                .toList();
        assertThat(uploads).singleElement().satisfies(row -> {
            assertThat(row.get("sha")).isEqualTo(tip.name());
            assertThat(row.get("ref")).isEqualTo(MAIN);
        });
    }

    private static ObjectId commit(Repository repository, String readme) throws Exception {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            TreeFormatter tree = new TreeFormatter();
            tree.append(
                    "README.md",
                    FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, readme.getBytes(StandardCharsets.UTF_8)));
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(inserter.insert(tree));
            PersonIdent ident = new PersonIdent("Test", "test@example.invalid");
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("approved");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            return id;
        }
    }

    private static void setRef(Repository repository, String name, ObjectId target) throws Exception {
        RefUpdate update = repository.updateRef(name);
        update.setNewObjectId(target);
        assertThat(update.forceUpdate()).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED);
    }
}

package io.github.jimisola.skillsgateway;

import io.github.jimisola.skillsgateway.approval.ApprovalService;
import io.github.jimisola.skillsgateway.auth.TokenService;
import io.github.jimisola.skillsgateway.ingestion.IngestionService;
import io.github.jimisola.skillsgateway.persistence.FetchLogRepository;
import io.github.jimisola.skillsgateway.persistence.Marketplace;
import io.github.jimisola.skillsgateway.persistence.MarketplaceRepository;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.jimisola.skillsgateway.persistence.SnapshotRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Shared fixture for all SVC verification tests: one Spring context (identical annotations across
 * subclasses keep it cached), JGit-built upstream fixtures, service-level arrangement helpers, and
 * a real-git process runner for facade end-to-end checks.
 *
 * <p>MockMvc is built manually because Boot 4 moved {@code @AutoConfigureMockMvc} into
 * spring-boot-webmvc-test, which is not a dependency of this project.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Dummy OIDC registration with explicit provider details: no discovery at startup.
            "spring.security.oauth2.client.registration.idp.client-id=test",
            "spring.security.oauth2.client.registration.idp.client-secret=test",
            "spring.security.oauth2.client.registration.idp.scope=openid",
            "spring.security.oauth2.client.registration.idp.authorization-grant-type=authorization_code",
            "spring.security.oauth2.client.registration.idp.redirect-uri={baseUrl}/login/oauth2/code/idp",
            "spring.security.oauth2.client.provider.idp.authorization-uri=https://idp.invalid/authorize",
            "spring.security.oauth2.client.provider.idp.token-uri=https://idp.invalid/token",
            "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.invalid/jwks",
            "skills-gateway.data-dir=target/test-git-data",
            // file is allowed only here so HTTP-level tests can register local fixtures.
            "skills-gateway.allowed-url-schemes=http,https,file",
            // Webhook dispatch is driven explicitly by the tests: the poller is off and the
            // backoff is compressed so retry behaviour is observable without long sleeps.
            "skills-gateway.webhooks.enabled=false",
            "skills-gateway.webhooks.base-backoff=100ms",
            "skills-gateway.webhooks.max-backoff=1s",
            "skills-gateway.webhooks.max-attempts=3",
            "skills-gateway.webhooks.timeout=2s",
            // Audit export passes are driven explicitly by the tests, and the commit-settling lag
            // is removed so an entry appended by the test is immediately exportable.
            "skills-gateway.audit-export.enabled=false",
            "skills-gateway.audit-export.lag=0s"
        })
abstract class AbstractGatewayTest {

    protected static final String DEFAULT_MANIFEST = """
            {
              "name": "test-marketplace",
              "owner": {"name": "Test"},
              "plugins": [
                {"name": "hello", "source": "./plugins/hello", "description": "test"}
              ]
            }
            """;

    protected static final String MANIFEST_PATH = ".claude-plugin/marketplace.json";

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @LocalServerPort
    protected int port;

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected IngestionService ingestionService;

    @Autowired
    protected ApprovalService approvalService;

    @Autowired
    protected MarketplaceRepository marketplaceRepository;

    @Autowired
    protected SnapshotRepository snapshotRepository;

    @Autowired
    protected TokenService tokenService;

    @Autowired
    protected FetchLogRepository fetchLogRepository;

    protected MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** Unique marketplace name matching {@code ^[a-z0-9][a-z0-9_-]*$}. */
    protected static String uniqueName(String prefix) {
        return prefix + Long.toString(System.nanoTime(), 36) + COUNTER.incrementAndGet();
    }

    /**
     * Creates an upstream marketplace repository on disk with JGit (never the git binary: the host
     * git config enforces GPG signing, which JGit ignores). Returns the work tree; its absolute
     * path doubles as the clone URL for registration.
     */
    protected static Path createUpstream(String manifestJson) throws IOException, GitAPIException {
        Path dir = newWorkDir("upstream");
        try (Git git =
                Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            Path manifest = dir.resolve(MANIFEST_PATH);
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, manifestJson);
            Path skill = dir.resolve("plugins/hello/skills/hello/SKILL.md");
            Files.createDirectories(skill.getParent());
            Files.writeString(skill, "# Hello\n\nA test skill that says hello.\n");
            commitAll(git, "initial marketplace content");
        }
        return dir;
    }

    /** Adds a commit upstream and returns the new head SHA. */
    protected static String addUpstreamCommit(Path upstreamDir, String marker) throws IOException, GitAPIException {
        try (Git git = Git.open(upstreamDir.toFile())) {
            Files.writeString(upstreamDir.resolve("NOTES.md"), marker + "\n");
            commitAll(git, "update " + marker);
            return git.getRepository().resolve(Constants.HEAD).name();
        }
    }

    /** Head SHA of any local repository (upstream fixture or a fresh clone). */
    protected static String headSha(Path workTree) throws IOException {
        try (Git git = Git.open(workTree.toFile())) {
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            Assertions.assertThat(head)
                    .as("HEAD of %s resolves to a commit", workTree)
                    .isNotNull();
            return head.name();
        }
    }

    private static void commitAll(Git git, String message) throws GitAPIException {
        git.add().addFilepattern(".").call();
        PersonIdent ident = new PersonIdent("Test", "test@example.com");
        git.commit()
                .setMessage(message)
                .setAuthor(ident)
                .setCommitter(ident)
                .setSign(false)
                .call();
    }

    protected static Path newWorkDir(String prefix) throws IOException {
        Path root = Path.of("target", "test-workdirs");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, prefix);
    }

    protected record Registered(Marketplace marketplace, Snapshot snapshot) {}

    /** Arrangement via services, not HTTP, so each test exercises only its own surface. */
    protected Registered registerAndIngest(String name, Path upstreamDir) {
        Marketplace marketplace = marketplaceRepository.register(
                name, upstreamDir.toAbsolutePath().toString());
        Snapshot snapshot = ingestionService.ingest(marketplace);
        return new Registered(marketplace, snapshot);
    }

    protected Snapshot approve(long snapshotId) {
        return approvalService.approve(snapshotId, "alice");
    }

    protected String newPat() {
        return tokenService.create("alice", "test").token();
    }

    /** Facade URL; pass a null PAT for an unauthenticated URL. */
    protected String facadeUrl(String marketplace, String pat) {
        String credentials = pat == null ? "" : "token:" + pat + "@";
        return "http://" + credentials + "127.0.0.1:" + port + "/git/" + marketplace;
    }

    protected record GitResult(int exitCode, String output) {}

    protected GitResult gitClone(String url, Path dest) throws IOException, InterruptedException {
        return git(null, "clone", url, dest.toString());
    }

    /** Runs the system git binary, isolated from the host git config. */
    protected static GitResult git(Path workDir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }
        Map<String, String> environment = builder.environment();
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_CONFIG_SYSTEM", "/dev/null");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("git %s timed out: %s".formatted(String.join(" ", args), output));
        }
        return new GitResult(process.exitValue(), output);
    }
}

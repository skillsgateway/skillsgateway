package dev.skillsgateway.server;

import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.auth.TokenService;
import dev.skillsgateway.server.ingestion.IngestionService;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotRepository;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Shared fixture for all SVC verification tests: one Spring context (declared by {@link
 * GatewayContext}, so every subclass lands on the same cache key), JGit-built upstream fixtures,
 * service-level arrangement helpers, and a real-git process runner for facade end-to-end checks.
 *
 * <p>MockMvc is built manually because Boot 4 moved {@code @AutoConfigureMockMvc} into
 * spring-boot-webmvc-test, which is not a dependency of this project.
 */
@GatewayContext
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

    /**
     * The default fixture's skill, conformant with the Agent Skills specification the
     * skill-conformance vetter pins (GW_INGEST_0028): a clean fixture has to mean a clean chain, or
     * every test that asserts one would be asserting around a standing finding instead.
     */
    protected static final String CONFORMANT_SKILL = """
            ---
            name: hello
            description: Says hello. Use when a test needs a trivial, well-formed skill.
            ---
            # Hello

            A test skill that says hello.
            """;

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

    /**
     * Every request carries a CSRF token by default (GW_AUTH_0030). The alternative was
     * {@code .with(csrf())} on some 143 mutating call sites, which would have made the token part
     * of what each suite appears to be about. {@code defaultRequest}'s post-processors are merged
     * ahead of the per-request ones rather than replacing them, so {@code .with(oidcLogin())} still
     * applies.
     *
     * <p>The cost is that no suite reached through this base can fail for a missing token, so none
     * of them proves the token is required. {@code CsrfEnforcementTests} does that on its own.
     */
    @BeforeEach
    void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .defaultRequest(MockMvcRequestBuilders.get("/").with(SecurityMockMvcRequestPostProcessors.csrf()))
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
     *
     * <p>The commit message carries a unique marker so that two fixtures built from the same
     * manifest never share a commit SHA. Without it they do: the tree, the fixed author identity
     * and the message are all identical, and a git timestamp has second granularity, so any two
     * fixtures created within the same second are the same commit. Tests would then couple through
     * every SHA-keyed table — the fetch ledger above all — in a way that depends on how fast the
     * suite happens to run. The marker changes only the commit object; the content is untouched.
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
            Files.writeString(skill, CONFORMANT_SKILL);
            commitAll(git, "initial marketplace content " + uniqueName("fixture"));
        }
        return dir;
    }

    /**
     * As {@link #createUpstream(String)}, with extra files committed on top — the fixture shape the
     * vetting tests need to plant content a vetter should react to.
     */
    protected static Path createUpstream(String manifestJson, Map<String, String> extraFiles)
            throws IOException, GitAPIException {
        Path dir = createUpstream(manifestJson);
        try (Git git = Git.open(dir.toFile())) {
            for (Map.Entry<String, String> file : extraFiles.entrySet()) {
                Path path = dir.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue());
            }
            commitAll(git, "add fixture content");
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

    /**
     * Arrangement via services, not HTTP, so each test exercises only its own surface. Neither
     * supply-side actor is recorded, which is deliberately the shape of a marketplace and a
     * snapshot from before those columns existed: nothing arranged this way can conflict with a
     * reviewer, so the four-eyes rule leaves every other test's fixtures exactly as they were.
     */
    protected Registered registerAndIngest(String name, Path upstreamDir) {
        return registerAndIngest(name, upstreamDir, null, null);
    }

    /** As above, with the supply-side identities the four-eyes rule reads (GW_APPROVAL_0010). */
    protected Registered registerAndIngest(String name, Path upstreamDir, String registrant, String ingestActor) {
        Marketplace marketplace = marketplaceRepository.register(
                name,
                upstreamDir.toAbsolutePath().toString(),
                null,
                Marketplace.ORIGIN_UPSTREAM,
                Marketplace.PUSH_APPEND_ONLY,
                registrant);
        Snapshot snapshot = ingestionService.ingest(marketplace, ingestActor);
        return new Registered(marketplace, snapshot);
    }

    protected Snapshot approve(long snapshotId) {
        return approvalService.approve(snapshotId, "alice").snapshot();
    }

    protected String newPat() {
        return tokenService.create("alice", "test").token();
    }

    /** Facade URL; pass a null PAT for an unauthenticated URL. */
    protected String facadeUrl(String marketplace, String pat) {
        String credentials = pat == null ? "" : "token:" + pat + "@";
        return "http://" + credentials + "127.0.0.1:" + port + "/git/" + marketplace;
    }

    /** Publish endpoint URL for a hosted marketplace (GW_FACADE_0007); a null PAT gives no credentials. */
    protected String publishUrl(String marketplace, String pat) {
        String credentials = pat == null ? "" : "token:" + pat + "@";
        return "http://" + credentials + "127.0.0.1:" + port + "/publish/" + marketplace;
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

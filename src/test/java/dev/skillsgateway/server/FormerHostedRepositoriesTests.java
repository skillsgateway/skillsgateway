package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * What the gateway tells an administrator about the repositories the removed hosting feature left
 * in its data directory (GW_AUTH_0091). Temporary, like the thing it tests.
 *
 * <p>Every case builds its own directories under the real {@code hosted} folder of the suite's data
 * directory and removes them again, so a list that is empty at the end of a case is empty because
 * the folders are, which is the property under test.
 */
class FormerHostedRepositoriesTests extends AbstractGatewayTest {

    private static final String ROUTE = "/api/v1/storage/former-hosted-repositories";

    @Autowired
    private SkillsGatewayProperties properties;

    private final List<Path> created = new java.util.ArrayList<>();

    @AfterEach
    void removeWhatWasCreated() throws IOException {
        for (Path path : created) {
            deleteTree(path);
        }
        created.clear();
    }

    private Path hostedDir() throws IOException {
        Path dir = properties.dataDir().resolve("hosted");
        Files.createDirectories(dir);
        return dir;
    }

    /** A real bare repository with one commit on main, as the removed feature left them. */
    private Path formerRepository(String name) throws Exception {
        Path origin = createUpstream(DEFAULT_MANIFEST);
        Path repository = hostedDir().resolve(name + ".git");
        created.add(repository);
        try (Git git = Git.cloneRepository()
                .setURI(origin.toUri().toString())
                .setBare(true)
                .setDirectory(repository.toFile())
                .call()) {
            assertThat(git.getRepository().resolve(Constants.R_HEADS + "main")).isNotNull();
        }
        return repository;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String digest(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted().toList()) {
                digest.update(root.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
                if (Files.isRegularFile(path)) {
                    digest.update(Files.readAllBytes(path));
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static RequestPostProcessor nobody() {
        return oidcLogin().idToken(token -> token.subject("nobody-" + System.nanoTime()));
    }

    private String list(RequestPostProcessor caller) throws Exception {
        return mockMvc.perform(get(ROUTE).with(caller))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0112"})
    void an_administrator_is_told_each_repository_its_location_and_last_commit() throws Exception {
        String name = uniqueName("former");
        Path repository = formerRepository(name);
        String before = digest(repository);

        String body = list(oidcLogin());

        List<String> names = JsonPath.read(body, "$[*].name");
        assertThat(names).contains(name);
        String entry = "$[?(@.name=='" + name + "')]";
        assertThat(JsonPath.<List<String>>read(body, entry + ".location"))
                .containsExactly(repository.toAbsolutePath().toString());
        try (Git git = Git.open(repository.toFile())) {
            String head =
                    git.getRepository().resolve(Constants.R_HEADS + "main").name();
            assertThat(JsonPath.<List<String>>read(body, entry + ".headSha")).containsExactly(head.substring(0, 7));
        }
        Instant committed = Instant.parse(
                JsonPath.<List<String>>read(body, entry + ".lastCommitAt").getFirst());
        assertThat(committed)
                .isBetween(
                        Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plusSeconds(60));
        assertThat(digest(repository))
                .as("listing writes nothing to the repository")
                .isEqualTo(before);
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0112"})
    void a_session_without_the_administrative_role_and_a_machine_credential_are_refused() throws Exception {
        formerRepository(uniqueName("former"));

        mockMvc.perform(get(ROUTE).with(nobody())).andExpect(status().isForbidden());

        TokenServiceCredential machine = machineCredential();
        mockMvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + machine.token()))
                .andExpect(status().is4xxClientError());
    }

    private record TokenServiceCredential(String token) {}

    private TokenServiceCredential machineCredential() {
        return new TokenServiceCredential(tokenService
                .createMachineCredential(
                        "machine-" + System.nanoTime(),
                        "former-hosted",
                        List.of("marketplaces:read"),
                        Instant.now().plus(1, ChronoUnit.DAYS),
                        "admin@example.invalid")
                .token());
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0112"})
    void a_symlink_a_stray_file_and_a_misnamed_directory_are_ignored() throws Exception {
        String real = uniqueName("former");
        formerRepository(real);
        Path outside = Files.createDirectories(newWorkDir("outside").resolve("elsewhere.git"));
        Path link = hostedDir().resolve(uniqueName("linked") + ".git");
        Files.createSymbolicLink(link, outside.toAbsolutePath());
        created.add(link);
        Path stray = Files.writeString(hostedDir().resolve(uniqueName("stray") + ".git"), "not a repository");
        created.add(stray);
        Path misnamed = Files.createDirectories(hostedDir().resolve("Not_A-Marketplace Name.git"));
        created.add(misnamed);
        Path notGit = Files.createDirectories(hostedDir().resolve(uniqueName("plain")));
        created.add(notGit);
        Path nested =
                Files.createDirectories(hostedDir().resolve(uniqueName("outer") + "/" + uniqueName("inner") + ".git"));
        created.add(nested.getParent());

        List<String> names = JsonPath.read(list(oidcLogin()), "$[*].name");

        assertThat(names).containsExactly(real);
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0112"})
    void the_list_is_empty_when_there_is_nothing_and_ends_when_the_folder_does() throws Exception {
        assertThat(JsonPath.<List<Object>>read(list(oidcLogin()), "$")).isEmpty();

        String name = uniqueName("former");
        Path repository = formerRepository(name);
        assertThat(JsonPath.<List<String>>read(list(oidcLogin()), "$[*].name")).containsExactly(name);

        deleteTree(repository);

        assertThat(JsonPath.<List<Object>>read(list(oidcLogin()), "$")).isEmpty();
    }
}

package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;

/**
 * The inventory diff a reviewer decides on (GW_0150): what approving a held snapshot would add to
 * what the marketplace already had approved.
 */
class ContentDiffTests extends AbstractGatewayTest {

    private static final String THREE_PLUGIN_MANIFEST =
            """
            {
              "name": "diff-marketplace",
              "owner": {"name": "Test"},
              "plugins": [
                {"name": "hello", "source": "./plugins/hello", "description": "greeting skills"},
                {"name": "review", "source": "./plugins/review", "description": "review skills"},
                {"name": "legacy", "source": "./plugins/legacy", "description": "skills on their way out"}
              ]
            }
            """;

    /** The same manifest with {@code legacy} dropped: a plugin a reviewer must be shown leaving. */
    private static final String TWO_PLUGIN_MANIFEST =
            """
            {
              "name": "diff-marketplace",
              "owner": {"name": "Test"},
              "plugins": [
                {"name": "hello", "source": "./plugins/hello", "description": "greeting skills"},
                {"name": "review", "source": "./plugins/review", "description": "review skills"}
              ]
            }
            """;

    @Test
    @SVCs({"SVC_GW_0150"})
    void snapshotContentDiffAgainstTheLastApprovedSnapshot() throws Exception {
        Path upstream = createUpstream(THREE_PLUGIN_MANIFEST);
        // hello/hello comes from the fixture; critique starts under hello and moves later.
        writeSkill(upstream, "plugins/hello/skills/critique", "SKILL.md", "# Critique\n");
        writeSkill(upstream, "plugins/review/skills/summarize", "SKILL.md", "# Summarize\n");
        // A file beside the definition: editing only this is the case a SKILL.md comparison misses.
        writeSkill(upstream, "plugins/review/skills/summarize", "helper.txt", "one\n");
        writeSkill(upstream, "plugins/legacy/skills/oldtool", "SKILL.md", "# Old tool\n");
        commit(upstream, "three plugins");

        Registered registered = registerAndIngest(uniqueName("corp"), upstream);
        long first = registered.snapshot().id();

        String noBaseline = diff(first);
        assertThat((Object) JsonPath.read(noBaseline, "$.baselineSnapshotId")).isNull();
        assertThat((Object) JsonPath.read(noBaseline, "$.baselineSha")).isNull();
        assertThat(this.<String>values(noBaseline, "$.plugins[*].status"))
                .containsOnly("added");
        assertThat(this.<String>values(noBaseline, "$.plugins[*].skills[*].status"))
                .containsOnly("added");
        assertThat((Integer) JsonPath.read(noBaseline, "$.summary.added")).isEqualTo(4);

        Snapshot approved = approve(first);

        Files.move(
                upstream.resolve("plugins/hello/skills/critique"), upstream.resolve("plugins/review/skills/critique"));
        Files.writeString(upstream.resolve("plugins/review/skills/summarize/helper.txt"), "two\n");
        writeSkill(upstream, "plugins/hello/skills/greet", "SKILL.md", "# Greet\n");
        deleteRecursively(upstream.resolve("plugins/legacy"));
        Files.writeString(upstream.resolve(MANIFEST_PATH), TWO_PLUGIN_MANIFEST);
        commit(upstream, "reorganise the marketplace");

        long second = ingestionService.ingest(registered.marketplace(), null).id();
        String body = diff(second);

        assertThat((Integer) JsonPath.read(body, "$.baselineSnapshotId")).isEqualTo((int) approved.id());
        assertThat((String) JsonPath.read(body, "$.baselineSha")).isEqualTo(approved.sha());

        assertThat(this.<String>values(body, "$.plugins[?(@.name == 'hello')].status"))
                .containsExactly("changed");
        assertThat(this.<String>values(body, "$.plugins[?(@.name == 'hello')].skills[?(@.name == 'hello')].status"))
                .containsExactly("unchanged");
        assertThat(this.<String>values(body, "$.plugins[?(@.name == 'hello')].skills[?(@.name == 'greet')].status"))
                .containsExactly("added");
        // The moved skill is reported once, on its new plugin — never as a removal under hello.
        assertThat(this.<String>values(body, "$.plugins[?(@.name == 'hello')].skills[*].name"))
                .containsExactlyInAnyOrder("hello", "greet");

        assertThat(this.<String>values(body, "$.plugins[?(@.name == 'review')].skills[?(@.name == 'critique')].status"))
                .containsExactly("moved");
        assertThat(this.<String>values(
                        body, "$.plugins[?(@.name == 'review')].skills[?(@.name == 'critique')].movedFromPlugin"))
                .containsExactly("hello");
        assertThat(this.<String>values(body, "$.plugins[?(@.name == 'review')].skills[?(@.name == 'summarize')].status"))
                .containsExactly("changed");

        assertThat(this.<String>values(body, "$.plugins[?(@.name == 'legacy')].status"))
                .containsExactly("removed");
        assertThat(this.<String>values(body, "$.plugins[?(@.name == 'legacy')].skills[?(@.name == 'oldtool')].status"))
                .containsExactly("removed");

        assertThat((Integer) JsonPath.read(body, "$.summary.added")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.summary.removed")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.summary.changed")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.summary.moved")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.summary.unchanged")).isEqualTo(1);

        mockMvc.perform(get("/api/snapshots/999999999/content-diff").with(oidcLogin()))
                .andExpect(status().isNotFound());
    }

    private String diff(long snapshotId) throws Exception {
        return mockMvc.perform(
                        get("/api/snapshots/%d/content-diff".formatted(snapshotId)).with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private <T> List<T> values(String body, String path) {
        return JsonPath.read(body, path);
    }

    private static void writeSkill(Path upstream, String directory, String file, String content) throws IOException {
        Path target = upstream.resolve(directory).resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    /**
     * Commits the work tree including deletions. {@code AddCommand} alone stages additions and
     * modifications, so a fixture that removes a plugin needs the update pass as well — without it
     * the "removed plugin" case would silently test nothing.
     */
    private static void commit(Path upstream, String message) throws IOException, GitAPIException {
        try (Git git = Git.open(upstream.toFile())) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            PersonIdent ident = new PersonIdent("Test", "test@example.com");
            git.commit()
                    .setMessage(message)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .setSign(false)
                    .call();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}

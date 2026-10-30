package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;

/**
 * Snapshot preview: tree-addressed file inspection with its caps and denials (GW_INGEST_0015) and the
 * diff against the served baseline (GW_INGEST_0016). The reads must show exactly the pinned commit —
 * these tests plant hostile-shaped content (oversized, binary, traversal paths) and assert the
 * service answers with metadata and refusals, never with the wrong bytes.
 */
class PreviewTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SKILL_PATH = "plugins/hello/skills/hello/SKILL.md";

    private final OidcLoginRequestPostProcessor alice = oidcLogin().idToken(token -> token.subject("alice"));

    @Test
    @SVCs({"SVC_GW_INGEST_0015"})
    void file_inspection_is_tree_addressed_capped_and_binary_aware() throws Exception {
        // A text file over the 128 KiB cap and a NUL-carrying binary blob, planted upstream.
        String oversized = "oversized line of fixture text\n".repeat(8000); // ~248 KiB
        String binary = "\0\1\2binary fixture bytes\0";
        Registered fixture = registerAndIngest(
                uniqueName("preview"),
                createUpstream(DEFAULT_MANIFEST, Map.of("data/huge.txt", oversized, "assets/logo.bin", binary)));
        long id = fixture.snapshot().id();

        // The tree lists exactly the pinned commit's paths, unbounded content notwithstanding.
        String treeBody = mockMvc.perform(
                        get("/api/v1/snapshots/{id}/files", id).with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha").value(fixture.snapshot().sha()))
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> paths = new ArrayList<>();
        for (JsonNode entry : MAPPER.readTree(treeBody).get("entries")) {
            paths.add(entry.get("path").asText());
        }
        assertThat(paths).contains(MANIFEST_PATH, SKILL_PATH, "data/huge.txt", "assets/logo.bin");

        // A text blob returns its content.
        mockMvc.perform(get("/api/v1/snapshots/{id}/file", id)
                        .param("path", SKILL_PATH)
                        .with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binary").value(false))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("Hello")));

        // Over the cap: truncated, with the marker saying so and the full size still reported.
        String hugeBody = mockMvc.perform(get("/api/v1/snapshots/{id}/file", id)
                        .param("path", "data/huge.txt")
                        .with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binary").value(false))
                .andExpect(jsonPath("$.truncated").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode huge = MAPPER.readTree(hugeBody);
        assertThat(huge.get("text").asText().length()).isEqualTo(128 * 1024);
        assertThat(huge.get("size").asLong()).isEqualTo(oversized.getBytes().length);

        // Binary: metadata only, never bytes dressed up as text.
        mockMvc.perform(get("/api/v1/snapshots/{id}/file", id)
                        .param("path", "assets/logo.bin")
                        .with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binary").value(true))
                .andExpect(jsonPath("$.text").doesNotExist());

        // Paths are matched against tree entries and nothing else: an absent path and every
        // traversal shape are simply not found — there is no filesystem underneath to escape to.
        for (String bad : List.of("no-such-file.md", "../../../etc/passwd", "/etc/passwd", "plugins/../..")) {
            mockMvc.perform(get("/api/v1/snapshots/{id}/file", id)
                            .param("path", bad)
                            .with(alice))
                    .andExpect(status().isNotFound());
        }

        // Unknown snapshot: not found, same as every other snapshot read.
        mockMvc.perform(get("/api/v1/snapshots/{id}/files", 999_999).with(alice))
                .andExpect(status().isNotFound());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0037"})
    void a_read_naming_a_line_returns_the_part_of_a_large_file_that_holds_it() throws Exception {
        // 6000 fixed-width lines, ~300 KiB: line n reads "line 0000n …", so a window's position is checkable.
        StringBuilder numbered = new StringBuilder();
        for (int n = 1; n <= 6000; n++) {
            numbered.append("line %05d of the numbered fixture text, padded\n".formatted(n));
        }
        Registered fixture = registerAndIngest(
                uniqueName("window"),
                createUpstream(DEFAULT_MANIFEST, Map.of("data/numbered.txt", numbered.toString())));
        long id = fixture.snapshot().id();

        JsonNode window = file(id, "data/numbered.txt", "5000");
        int first = window.get("firstLine").asInt();
        String text = window.get("text").asText();
        String[] lines = text.split("\n", -1);
        assertThat(window.get("truncated").asBoolean()).isTrue();
        assertThat(text.length()).isLessThanOrEqualTo(128 * 1024);
        // Starts at a line boundary, numbered by firstLine, with context before the line and the line itself.
        assertThat(lines[0]).startsWith("line %05d ".formatted(first));
        assertThat(first).isBetween(2, 5000 - 1000);
        assertThat(lines[5000 - first]).startsWith("line 05000 ");

        // No line, a line inside the head, and a line past the end: the head, numbered from 1.
        for (String line : new String[] {null, "10", "999999"}) {
            JsonNode head = file(id, "data/numbered.txt", line);
            assertThat(head.get("firstLine").asInt()).as(line).isEqualTo(1);
            assertThat(head.get("text").asText())
                    .as(line)
                    .startsWith("line 00001 ")
                    .hasSize(128 * 1024);
        }

        // A file within the cap is whole whatever line is named.
        JsonNode small = file(id, SKILL_PATH, "3");
        assertThat(small.get("firstLine").asInt()).isEqualTo(1);
        assertThat(small.get("truncated").asBoolean()).isFalse();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0038"})
    void a_read_with_a_blank_path_or_a_line_below_one_is_a_bad_request() throws Exception {
        long id = registerAndIngest(uniqueName("blank"), createUpstream(DEFAULT_MANIFEST, Map.of()))
                .snapshot()
                .id();
        for (String blank : List.of("", "   ")) {
            mockMvc.perform(get("/api/v1/snapshots/{id}/file", id)
                            .param("path", blank)
                            .with(alice))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/v1/snapshots/{id}/file", id)
                        .param("path", SKILL_PATH)
                        .param("line", "0")
                        .with(alice))
                .andExpect(status().isBadRequest());
    }

    private JsonNode file(long id, String path, String line) throws Exception {
        var request = get("/api/v1/snapshots/{id}/file", id).param("path", path).with(alice);
        if (line != null) {
            request = request.param("line", line);
        }
        return MAPPER.readTree(mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0016"})
    void the_diff_names_the_served_baseline_and_the_exact_delta_or_its_absence() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST, Map.of("docs/REMOVE.md", "# Goes away\n"));
        Registered fixture = registerAndIngest(uniqueName("diffbase"), upstream);
        long baseId = fixture.snapshot().id();
        String baseSha = fixture.snapshot().sha();
        approve(baseId);

        // The served snapshot against itself: the baseline is itself, and nothing differs.
        mockMvc.perform(get("/api/v1/snapshots/{id}/diff", baseId).with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineSha").value(baseSha))
                .andExpect(jsonPath("$.entries").isEmpty());

        // Upstream moves: one file modified, one added, one removed.
        changeUpstream(upstream);
        long heldId = ingestionService
                .ingest(
                        marketplaceRepository
                                .findById(fixture.marketplace().id())
                                .orElseThrow(),
                        null)
                .id();

        String body = mockMvc.perform(get("/api/v1/snapshots/{id}/diff", heldId).with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineSha").value(baseSha))
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode diff = MAPPER.readTree(body);
        Map<String, JsonNode> byPath = new java.util.HashMap<>();
        for (JsonNode entry : diff.get("entries")) {
            byPath.put(entry.get("path").asText(), entry);
        }
        assertThat(byPath.keySet()).containsExactlyInAnyOrder(SKILL_PATH, "docs/NEW.md", "docs/REMOVE.md");
        assertThat(byPath.get(SKILL_PATH).get("type").asText()).isEqualTo("modified");
        assertThat(byPath.get(SKILL_PATH).get("diff").asText()).contains("+Now with a changed instruction.");
        assertThat(byPath.get("docs/NEW.md").get("type").asText()).isEqualTo("added");
        assertThat(byPath.get("docs/REMOVE.md").get("type").asText()).isEqualTo("removed");
        assertThat(byPath.get("docs/REMOVE.md").get("diff").asText()).contains("-# Goes away");

        // A marketplace serving nothing has no baseline; the honest answer is "all of it is new".
        Registered unserved = registerAndIngest(uniqueName("diffnone"), createUpstream(DEFAULT_MANIFEST));
        String noneBody = mockMvc.perform(
                        get("/api/v1/snapshots/{id}/diff", unserved.snapshot().id())
                                .with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineSha").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode none = MAPPER.readTree(noneBody);
        assertThat(none.get("entries").size()).isGreaterThan(0);
        for (JsonNode entry : none.get("entries")) {
            assertThat(entry.get("type").asText()).isEqualTo("added");
        }
    }

    /** Modifies the skill, adds a file, removes a file — the three change types in one commit. */
    private static void changeUpstream(Path upstream) throws IOException, GitAPIException {
        try (Git git = Git.open(upstream.toFile())) {
            Files.writeString(upstream.resolve(SKILL_PATH), "# Hello\n\nNow with a changed instruction.\n");
            Files.writeString(upstream.resolve("docs/NEW.md"), "# Brand new\n");
            Files.delete(upstream.resolve("docs/REMOVE.md"));
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            PersonIdent ident = new PersonIdent("Test", "test@example.com");
            git.commit()
                    .setMessage("modify, add and remove for the diff fixture")
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .setSign(false)
                    .call();
        }
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0073"})
    void a_diff_of_files_built_to_defeat_the_diff_heuristic_answers_quickly() throws Exception {
        // Two random sequences over two lines: most lines repeat on both sides, which is what sends
        // a histogram diff into its quadratic fallback.
        Path upstream = createUpstream(DEFAULT_MANIFEST, Map.of("docs/data.txt", randomLines(1)));
        Registered fixture = registerAndIngest(uniqueName("diffbound"), upstream);
        approve(fixture.snapshot().id());
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(upstream.toFile())) {
            java.nio.file.Files.writeString(upstream.resolve("docs/data.txt"), randomLines(2));
            git.add().addFilepattern(".").call();
            git.commit().setMessage("every line changed").setSign(false).call();
        }
        long heldId = ingestionService
                .ingest(
                        marketplaceRepository
                                .findById(fixture.marketplace().id())
                                .orElseThrow(),
                        null)
                .id();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5),
                () -> mockMvc.perform(get("/api/v1/snapshots/{id}/diff", heldId).with(alice))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.entries[0].path").value("docs/data.txt")));
    }

    private static String randomLines(long seed) {
        java.util.Random random = new java.util.Random(seed);
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 64_000; i++) {
            lines.append(random.nextBoolean() ? "x\n" : "y\n");
        }
        return lines.toString();
    }
}

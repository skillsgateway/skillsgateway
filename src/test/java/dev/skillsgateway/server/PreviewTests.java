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
 * Snapshot preview: tree-addressed file inspection with its caps and denials (GW_0080) and the
 * diff against the served baseline (GW_0081). The reads must show exactly the pinned commit —
 * these tests plant hostile-shaped content (oversized, binary, traversal paths) and assert the
 * service answers with metadata and refusals, never with the wrong bytes.
 */
class PreviewTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SKILL_PATH = "plugins/hello/skills/hello/SKILL.md";

    private final OidcLoginRequestPostProcessor alice = oidcLogin().idToken(token -> token.subject("alice"));

    @Test
    @SVCs({"SVC_GW_0080"})
    void file_inspection_is_tree_addressed_capped_and_binary_aware() throws Exception {
        // A text file over the 128 KiB cap and a NUL-carrying binary blob, planted upstream.
        String oversized = "oversized line of fixture text\n".repeat(8000); // ~248 KiB
        String binary = "\0\1\2binary fixture bytes\0";
        Registered fixture = registerAndIngest(
                uniqueName("preview"),
                createUpstream(DEFAULT_MANIFEST, Map.of("data/huge.txt", oversized, "assets/logo.bin", binary)));
        long id = fixture.snapshot().id();

        // The tree lists exactly the pinned commit's paths, unbounded content notwithstanding.
        String treeBody = mockMvc.perform(get("/api/snapshots/{id}/files", id).with(alice))
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
        mockMvc.perform(get("/api/snapshots/{id}/file", id)
                        .param("path", SKILL_PATH)
                        .with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binary").value(false))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("Hello")));

        // Over the cap: truncated, with the marker saying so and the full size still reported.
        String hugeBody = mockMvc.perform(get("/api/snapshots/{id}/file", id)
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
        mockMvc.perform(get("/api/snapshots/{id}/file", id)
                        .param("path", "assets/logo.bin")
                        .with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binary").value(true))
                .andExpect(jsonPath("$.text").doesNotExist());

        // Paths are matched against tree entries and nothing else: an absent path and every
        // traversal shape are simply not found — there is no filesystem underneath to escape to.
        for (String bad : List.of("no-such-file.md", "../../../etc/passwd", "/etc/passwd", "plugins/../..")) {
            mockMvc.perform(get("/api/snapshots/{id}/file", id)
                            .param("path", bad)
                            .with(alice))
                    .andExpect(status().isNotFound());
        }

        // Unknown snapshot: not found, same as every other snapshot read.
        mockMvc.perform(get("/api/snapshots/{id}/files", 999_999).with(alice)).andExpect(status().isNotFound());
    }

    @Test
    @SVCs({"SVC_GW_0081"})
    void the_diff_names_the_served_baseline_and_the_exact_delta_or_its_absence() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST, Map.of("docs/REMOVE.md", "# Goes away\n"));
        Registered fixture = registerAndIngest(uniqueName("diffbase"), upstream);
        long baseId = fixture.snapshot().id();
        String baseSha = fixture.snapshot().sha();
        approve(baseId);

        // The served snapshot against itself: the baseline is itself, and nothing differs.
        mockMvc.perform(get("/api/snapshots/{id}/diff", baseId).with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineSha").value(baseSha))
                .andExpect(jsonPath("$.entries").isEmpty());

        // Upstream moves: one file modified, one added, one removed.
        changeUpstream(upstream);
        long heldId = ingestionService
                .ingest(marketplaceRepository
                        .findById(fixture.marketplace().id())
                        .orElseThrow())
                .id();

        String body = mockMvc.perform(get("/api/snapshots/{id}/diff", heldId).with(alice))
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
                        get("/api/snapshots/{id}/diff", unserved.snapshot().id())
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
}

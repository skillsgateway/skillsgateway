package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The whole of a snapshot is reachable, whatever its size (GW_APPROVAL_0025–0028): a served snapshot
 * and a held successor wider than every page — one directory of 601 children, more than 2000 files,
 * more than 500 changed paths — read to their ends a page at a time, with totals over the whole set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotBrowsingTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int WIDE = 600;
    private static final int BULK_DIRS = 15;
    private static final int BULK_PER_DIR = 100;

    private final OidcLoginRequestPostProcessor alice = oidcLogin().idToken(token -> token.subject("alice"));

    private long held;
    private String baseSha;
    private long unserved;

    @BeforeAll
    void arrange() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < WIDE; i++) {
            files.put("wide/f%04d.txt".formatted(i), "line\n");
        }
        for (int d = 0; d < BULK_DIRS; d++) {
            for (int f = 0; f < BULK_PER_DIR; f++) {
                files.put("bulk/d%02d/item-%02d.txt".formatted(d, f), "bulk\n");
            }
        }
        files.put("gone/old.txt", "old\n");
        Path upstream = createUpstream(DEFAULT_MANIFEST, files);
        Registered base = registerAndIngest(uniqueName("browse"), upstream);
        baseSha = base.snapshot().sha();
        approve(base.snapshot().id());

        // 600 modified, a text file and a binary one added, one removed with its whole directory.
        try (Git git = Git.open(upstream.toFile())) {
            for (int i = 0; i < WIDE; i++) {
                Files.writeString(upstream.resolve("wide/f%04d.txt".formatted(i)), "line\nnew\n");
            }
            Files.writeString(upstream.resolve("wide/new.txt"), "new\n");
            Files.createDirectories(upstream.resolve("bin"));
            Files.write(upstream.resolve("bin/logo.bin"), new byte[] {0, 1, 2, 0, 3});
            Files.delete(upstream.resolve("gone/old.txt"));
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            PersonIdent ident = new PersonIdent("Test", "test@example.com");
            git.commit()
                    .setMessage("widen the delta past every page")
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .setSign(false)
                    .call();
        }
        held = ingestionService
                .ingest(marketplaceRepository.findById(base.marketplace().id()).orElseThrow(), null)
                .id();
        unserved = registerAndIngest(uniqueName("browse-none"), createUpstream(DEFAULT_MANIFEST, files))
                .snapshot()
                .id();
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request.with(alice))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(body);
    }

    private JsonNode tree(String dir, int offset) throws Exception {
        return read(get("/api/v1/snapshots/{id}/tree", held).param("dir", dir).param("offset", String.valueOf(offset)));
    }

    /** Every page of a paged read, walked by its own nextOffset until it says there is no more. */
    private List<JsonNode> pages(java.util.function.IntFunction<MockHttpServletRequestBuilder> page) throws Exception {
        List<JsonNode> pages = new ArrayList<>();
        int offset = 0;
        while (true) {
            JsonNode body = read(page.apply(offset));
            pages.add(body);
            if (!body.hasNonNull("nextOffset")) {
                assertThat(body.get("truncated").asBoolean()).isFalse();
                return pages;
            }
            assertThat(body.get("truncated").asBoolean()).isTrue();
            offset = body.get("nextOffset").asInt();
        }
    }

    private int totalFiles() throws Exception {
        return read(get("/api/v1/snapshots/{id}/files", held)).get("total").asInt();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0025", "SVC_GW_APPROVAL_0026"})
    void every_child_of_a_directory_wider_than_a_page_is_reached_with_its_change_and_the_counts_are_totals()
            throws Exception {
        JsonNode root = tree("", 0);
        assertThat(root.path("baselineSha").asText()).isEqualTo(baseSha);
        assertThat(root.get("files").asInt()).isEqualTo(totalFiles());
        // 600 modified, two added, one removed — the whole delta, though no page holds it.
        assertThat(root.get("changed").asInt()).isEqualTo(603);

        Map<String, JsonNode> top = new HashMap<>();
        root.get("entries").forEach(entry -> top.put(entry.get("name").asText(), entry));
        assertThat(top.get("wide").get("kind").asText()).isEqualTo("directory");
        assertThat(top.get("wide").get("files").asInt()).isEqualTo(WIDE + 1);
        assertThat(top.get("wide").get("changed").asInt()).isEqualTo(WIDE + 1);
        assertThat(top.get("bulk").get("files").asInt()).isEqualTo(BULK_DIRS * BULK_PER_DIR);
        assertThat(top.get("bulk").get("changed").asInt()).isZero();
        // A directory the snapshot removes entirely is still listed, with nothing in it and one change.
        assertThat(top.get("gone").get("files").asInt()).isZero();
        assertThat(top.get("gone").get("changed").asInt()).isEqualTo(1);
        // Directories first, then files, each by name.
        List<String> order = new ArrayList<>();
        root.get("entries")
                .forEach(entry -> order.add(
                        entry.get("kind").asText() + ":" + entry.get("name").asText()));
        assertThat(order)
                .isSortedAccordingTo(java.util.Comparator.comparing((String key) -> key.startsWith("file:"))
                        .thenComparing(key -> key.substring(key.indexOf(':') + 1)));
        assertThat(order).contains("directory:bin", "directory:gone", "directory:wide");

        // The wide directory, page by page: every child exactly once.
        List<JsonNode> pages = pages(offset ->
                get("/api/v1/snapshots/{id}/tree", held).param("dir", "wide").param("offset", String.valueOf(offset)));
        assertThat(pages).hasSize(2);
        assertThat(pages.getFirst().get("entries").size()).isEqualTo(500);
        Set<String> seen = new HashSet<>();
        for (JsonNode page : pages) {
            assertThat(page.get("total").asInt()).isEqualTo(WIDE + 1);
            assertThat(page.get("files").asInt()).isEqualTo(WIDE + 1);
            for (JsonNode child : page.get("entries")) {
                assertThat(seen.add(child.get("path").asText())).isTrue();
                assertThat(child.get("kind").asText()).isEqualTo("file");
                assertThat(child.get("size").asLong()).isPositive();
                String expected = child.get("name").asText().equals("new.txt") ? "added" : "modified";
                assertThat(child.get("status").asText()).isEqualTo(expected);
            }
        }
        assertThat(seen).hasSize(WIDE + 1).contains("wide/f0000.txt", "wide/f0599.txt", "wide/new.txt");

        // The removed file is a child of its directory, with no blob to size.
        JsonNode gone = tree("gone", 0).get("entries").get(0);
        assertThat(gone.get("path").asText()).isEqualTo("gone/old.txt");
        assertThat(gone.get("status").asText()).isEqualTo("removed");
        assertThat(gone.hasNonNull("size")).isFalse();

        // An unchanged file carries no status; an offset past the end is an empty last page.
        JsonNode bulk = tree("bulk/d00", 0);
        assertThat(bulk.get("entries").get(0).hasNonNull("status")).isFalse();
        JsonNode past = tree("wide", 10_000);
        assertThat(past.get("entries")).isEmpty();
        assertThat(past.hasNonNull("nextOffset")).isFalse();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0025"})
    void a_directory_absent_from_both_trees_or_naming_a_file_is_not_found() throws Exception {
        for (String bad :
                List.of("no-such-dir", "wide/f0000.txt", "../../etc", "/etc", "wide/..", "wide/../bulk", "/wide")) {
            mockMvc.perform(get("/api/v1/snapshots/{id}/tree", held)
                            .param("dir", bad)
                            .with(alice))
                    .andExpect(status().isNotFound());
        }
        for (String route : List.of("tree", "files", "diff")) {
            mockMvc.perform(get("/api/v1/snapshots/{id}/" + route, held)
                            .param("offset", "-1")
                            .with(alice))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0027", "SVC_GW_APPROVAL_0026"})
    void the_listing_pages_to_its_end_and_a_search_finds_paths_anywhere_regardless_of_case() throws Exception {
        List<JsonNode> pages =
                pages(offset -> get("/api/v1/snapshots/{id}/files", held).param("offset", String.valueOf(offset)));
        int total = pages.getFirst().get("total").asInt();
        assertThat(total).isGreaterThan(2000);
        assertThat(pages.getFirst().get("entries").size()).isEqualTo(2000);
        Set<String> seen = new HashSet<>();
        for (JsonNode page : pages) {
            page.get("entries")
                    .forEach(entry ->
                            assertThat(seen.add(entry.get("path").asText())).isTrue());
        }
        assertThat(seen).hasSize(total).contains("wide/new.txt", "bulk/d14/item-99.txt", "bin/logo.bin");

        JsonNode search = read(get("/api/v1/snapshots/{id}/files", held).param("q", "  WIDE/F000"));
        assertThat(search.get("total").asInt()).isEqualTo(10);
        assertThat(search.get("truncated").asBoolean()).isFalse();
        search.get("entries")
                .forEach(entry -> assertThat(entry.get("path").asText()).startsWith("wide/f000"));

        // A match deep in the tree, beyond the first page of the unfiltered listing.
        JsonNode deep = read(get("/api/v1/snapshots/{id}/files", held).param("q", "d14/item-99"));
        assertThat(deep.get("total").asInt()).isEqualTo(1);
        assertThat(deep.get("entries").get(0).get("path").asText()).isEqualTo("bulk/d14/item-99.txt");

        assertThat(read(get("/api/v1/snapshots/{id}/files", held).param("q", "../"))
                        .get("total")
                        .asInt())
                .isZero();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0028", "SVC_GW_APPROVAL_0026"})
    void the_diff_is_read_to_its_end_a_page_at_a_time_with_exact_totals_or_narrowed_to_a_path() throws Exception {
        List<JsonNode> pages =
                pages(offset -> get("/api/v1/snapshots/{id}/diff", held).param("offset", String.valueOf(offset)));
        assertThat(pages).hasSize(2);
        assertThat(pages.getFirst().get("entries").size()).isEqualTo(500);
        Map<String, String> types = new HashMap<>();
        for (JsonNode page : pages) {
            assertThat(page.get("baselineSha").asText()).isEqualTo(baseSha);
            assertThat(page.get("total").asInt()).isEqualTo(603);
            JsonNode summary = page.get("summary");
            assertThat(summary.get("added").asInt()).isEqualTo(2);
            assertThat(summary.get("modified").asInt()).isEqualTo(WIDE);
            assertThat(summary.get("removed").asInt()).isEqualTo(1);
            assertThat(summary.get("binary").asInt()).isEqualTo(1);
            // One line added to each of 600 files, one new one-line file; one one-line file removed.
            assertThat(summary.get("linesAdded").asLong()).isEqualTo(WIDE + 1);
            assertThat(summary.get("linesRemoved").asLong()).isEqualTo(1);
            for (JsonNode entry : page.get("entries")) {
                assertThat(types.put(
                                entry.get("path").asText(), entry.get("type").asText()))
                        .isNull();
            }
        }
        assertThat(types).hasSize(603).containsEntry("gone/old.txt", "removed").containsEntry("bin/logo.bin", "added");

        JsonNode one = read(get("/api/v1/snapshots/{id}/diff", held).param("path", "wide/f0001.txt"));
        assertThat(one.get("total").asInt()).isEqualTo(1);
        assertThat(one.get("entries").get(0).get("type").asText()).isEqualTo("modified");
        assertThat(one.get("entries").get(0).get("diff").asText()).contains("+new");
        assertThat(one.get("summary").get("linesAdded").asLong()).isEqualTo(1);

        JsonNode folder = read(get("/api/v1/snapshots/{id}/diff", held).param("path", "wide"));
        assertThat(folder.get("total").asInt()).isEqualTo(WIDE + 1);
        // A pathspec that is a prefix of a name is not a directory: "wid" narrows to nothing.
        for (String nothing : List.of("wid", "../../etc/passwd", "/wide", "wide/..", "bulk")) {
            JsonNode empty = read(get("/api/v1/snapshots/{id}/diff", held).param("path", nothing));
            assertThat(empty.get("total").asInt()).as(nothing).isZero();
            assertThat(empty.get("entries")).as(nothing).isEmpty();
        }

        // Nothing served: every path is added, and the totals say how many there are.
        JsonNode none = read(get("/api/v1/snapshots/{id}/diff", unserved));
        assertThat(none.hasNonNull("baselineSha")).isFalse();
        int files =
                read(get("/api/v1/snapshots/{id}/files", unserved)).get("total").asInt();
        assertThat(none.get("total").asInt()).isEqualTo(files);
        assertThat(none.get("summary").get("added").asInt()).isEqualTo(files);
        assertThat(none.get("entries").size()).isEqualTo(500);
        assertThat(none.get("nextOffset").asInt()).isEqualTo(500);
    }
}

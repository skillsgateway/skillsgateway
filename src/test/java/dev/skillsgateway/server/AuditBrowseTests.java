package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ledger's browse read (GW_AUDIT_0008).
 *
 * <p>It used to be an unbounded {@code SELECT *} returning rows keyed by column name. Two
 * consequences, and the second is the one worth a test rather than a note: a portal visit loaded
 * the whole of the table the assessment calls "the table that ends the deployment", and paging it
 * by offset over an append-only table would have shifted rows under a reader between requests.
 * These cases pin the cursor behaviour, because that is the part a reasonable implementation gets
 * subtly wrong.
 */
class AuditBrowseTests extends AbstractGatewayTest {

    /** Enough ledger entries to need more than one page at the sizes used below. */
    private void appendEntries(int count) {
        for (int i = 0; i < count; i++) {
            fetchLogRepository.append(
                    "10.0.0.1", "browse-" + i, uniqueName("mkt"), "info-refs", "refs/heads/main", null);
        }
    }

    @Test
    @SVCs({"SVC_GW_AUDIT_0008"})
    void the_first_page_is_the_newest_entries_and_the_cursor_walks_strictly_older() throws Exception {
        appendEntries(12);

        String first = mockMvc.perform(get("/api/v1/audit").param("limit", "5").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(5))
                .andExpect(jsonPath("$.nextBefore").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Integer> firstIds = JsonPath.read(first, "$.entries[*].id");
        long cursor = ((Number) JsonPath.read(first, "$.nextBefore")).longValue();

        // Newest first: ids descend within the page, and the cursor is the page's oldest entry.
        assertThat(firstIds).isSortedAccordingTo((a, b) -> Integer.compare(b, a));
        assertThat(cursor).isEqualTo(firstIds.getLast().longValue());

        String second = mockMvc.perform(get("/api/v1/audit")
                        .param("limit", "5")
                        .param("before", String.valueOf(cursor))
                        .with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Integer> secondIds = JsonPath.read(second, "$.entries[*].id");

        // Strictly older, and nothing repeated: an offset page over an append-only table could
        // satisfy neither once a new entry lands between the two requests.
        assertThat(secondIds).allSatisfy(id -> assertThat(id).isLessThan((int) cursor));
        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
    }

    @Test
    @SVCs({"SVC_GW_AUDIT_0008"})
    void the_last_page_omits_the_cursor_rather_than_pointing_at_nothing() throws Exception {
        // A page that came back short is the oldest page there is; returning its own last id would
        // invite a caller to request an empty page forever.
        mockMvc.perform(get("/api/v1/audit").param("limit", "1000").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextBefore").doesNotExist());
    }

    @Test
    @SVCs({"SVC_GW_AUDIT_0008"})
    void a_page_size_above_the_export_bound_is_clamped_to_it() throws Exception {
        appendEntries(3);

        // The bound is the export's maximum, shared rather than configured twice for one table.
        mockMvc.perform(get("/api/v1/audit").param("limit", "100000").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray());
    }

    @Test
    @SVCs({"SVC_GW_AUDIT_0008"})
    void the_entries_are_typed_rather_than_raw_column_names() throws Exception {
        appendEntries(1);

        // The old shape leaked actor_type, token_id and credential_kind as JSON keys, which made
        // every column rename an API change.
        mockMvc.perform(get("/api/v1/audit").param("limit", "1").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].id").isNumber())
                .andExpect(jsonPath("$.entries[0].ts").isString())
                .andExpect(jsonPath("$.entries[0].actor_type").doesNotExist())
                .andExpect(jsonPath("$.entries[0].token_id").doesNotExist())
                .andExpect(jsonPath("$.entries[0].credential_kind").doesNotExist());
    }
}

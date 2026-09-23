package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.policy.SnapshotFactsRepository;
import dev.skillsgateway.server.policy.SnapshotFactsService;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Snapshot facts as recorded state (GW_INGEST_0036). */
class SnapshotFactsPersistenceTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TWO_PLUGINS = """
            {
              "name": "test-marketplace",
              "owner": {"name": "Test"},
              "plugins": [
                {"name": "hello", "source": "./plugins/hello", "description": "test"},
                {
                  "description": "second",
                  "name": "second",
                  "source": "./plugins/hello"
                }
              ]
            }
            """;

    @Autowired
    private SnapshotFactsService factsService;

    @Autowired
    private SnapshotFactsRepository factsRepository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @SVCs({"SVC_GW_INGEST_0036"})
    void ingestion_records_the_facts_without_the_state_and_indexes_each_plugin_at_its_manifest_line() throws Exception {
        Registered registered = registerAndIngest(uniqueName("facts"), createUpstream(TWO_PLUGINS));
        Snapshot snapshot = registered.snapshot();

        String json = factsRepository.find(snapshot.id()).orElseThrow().factsJson();
        assertThat(json).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> recorded = MAPPER.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> recordedSnapshot = (Map<String, Object>) recorded.get("snapshot");
        assertThat(recordedSnapshot).doesNotContainKey("state");

        // Equal to a fresh build in every value but the state, which is supplied on read.
        Map<String, Object> built = factsService.build(snapshot, registered.marketplace());
        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(factsService.load(snapshot, registered.marketplace()))))
                .isEqualTo(MAPPER.readTree(MAPPER.writeValueAsString(built)));

        assertThat(factsRepository.pluginNames(snapshot.id()))
                .containsExactly(
                        new SnapshotFactsRepository.PluginName("hello", ".claude-plugin/marketplace.json:5"),
                        new SnapshotFactsRepository.PluginName("second", ".claude-plugin/marketplace.json:8"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0036"})
    void the_state_read_back_is_always_the_current_one() throws Exception {
        Registered registered = registerAndIngest(uniqueName("facts"), createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();

        assertThat(stateInFacts(id, registered)).isEqualTo(Snapshot.HELD);
        approve(id);
        assertThat(stateInFacts(id, registered)).isEqualTo(Snapshot.APPROVED);
        snapshotRepository.revoke(id, "revet-policy", "test", Snapshot.REVOKED_BY_REVET);
        assertThat(stateInFacts(id, registered)).isEqualTo(Snapshot.REVOKED);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0036"})
    void ingesting_the_same_commit_again_rewrites_nothing() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(uniqueName("facts"), upstream);
        long id = registered.snapshot().id();
        OffsetDateTime builtAt = builtAt(id);

        Snapshot again = ingestionService.ingest(registered.marketplace(), null);
        assertThat(again.id()).isEqualTo(id);
        factsService.record(again, registered.marketplace());
        assertThat(builtAt(id)).isEqualTo(builtAt);
        assertThat(factsRepository.pluginNames(id)).hasSize(1);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0036"})
    void the_policy_gate_decides_on_the_recorded_facts_not_on_a_fresh_build() throws Exception {
        String marketplace = uniqueName("facts");
        Registered registered = registerAndIngest(marketplace, createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();
        // Only the recorded facts say this; the pinned commit does not.
        jdbc.sql("UPDATE snapshot_facts SET facts = jsonb_set(facts, '{plugins,0,name}', '\"recorded-only\"')"
                        + " WHERE snapshot_id = :id")
                .param("id", id)
                .update();
        String rule = uniqueName("recorded");
        createRule(
                rule,
                "snapshot.marketplace == \"%s\" && plugins.exists(p, p.name == \"recorded-only\")"
                        .formatted(marketplace));
        try {
            mockMvc.perform(post("/api/v1/snapshots/{id}/approve", id)
                            .with(oidcLogin().idToken(token -> token.subject("alice"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.denials[0].rule").value(rule))
                    .andExpect(jsonPath("$.denials[0].outcome").value("matched"));
        } finally {
            deleteRule(rule);
        }
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0036"})
    void facts_that_cannot_be_built_are_not_recorded_but_the_names_are_and_the_policy_gate_still_refuses()
            throws Exception {
        String marketplace = uniqueName("facts");
        String oversized = "---\nname: big\ndescription: too big to trust\n---\n" + "x".repeat(300 * 1024);
        Registered registered = registerAndIngest(
                marketplace, createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/skills/big/SKILL.md", oversized)));
        long id = registered.snapshot().id();

        assertThat(factsRepository.find(id))
                .hasValueSatisfying(recorded -> assertThat(recorded.factsJson()).isNull());
        assertThat(factsRepository.pluginNames(id))
                .containsExactly(new SnapshotFactsRepository.PluginName("hello", ".claude-plugin/marketplace.json:5"));

        String rule = uniqueName("anything");
        createRule(rule, "snapshot.marketplace == \"%s\"".formatted(marketplace));
        try {
            mockMvc.perform(post("/api/v1/snapshots/{id}/approve", id)
                            .with(oidcLogin().idToken(token -> token.subject("alice"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.denials[0].rule").value(rule))
                    .andExpect(jsonPath("$.denials[0].outcome")
                            .value(org.hamcrest.Matchers.allOf(
                                    org.hamcrest.Matchers.startsWith("error"),
                                    org.hamcrest.Matchers.containsString("exceeds"))));
        } finally {
            deleteRule(rule);
        }
    }

    private String stateInFacts(long id, Registered registered) {
        Snapshot current = snapshotRepository.findById(id).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshotFacts = new HashMap<>((Map<String, Object>)
                factsService.load(current, registered.marketplace()).get("snapshot"));
        return (String) snapshotFacts.get("state");
    }

    private OffsetDateTime builtAt(long id) {
        return jdbc.sql("SELECT built_at FROM snapshot_facts WHERE snapshot_id = :id")
                .param("id", id)
                .query(OffsetDateTime.class)
                .single();
    }

    private void createRule(String name, String expression) throws Exception {
        mockMvc.perform(post("/api/v1/policy/rules")
                        .with(oidcLogin().idToken(token -> token.subject("root")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "name",
                                name,
                                "description",
                                "facts fixture",
                                "expression",
                                expression,
                                "enabled",
                                true))))
                .andExpect(status().isOk());
    }

    private void deleteRule(String name) throws Exception {
        mockMvc.perform(delete("/api/v1/policy/rules/{name}", name)
                        .with(oidcLogin().idToken(token -> token.subject("root"))))
                .andExpect(status().isOk());
    }
}

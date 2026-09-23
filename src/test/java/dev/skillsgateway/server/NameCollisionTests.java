package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.approval.FourEyesGate;
import dev.skillsgateway.server.approval.NameCollisionException;
import dev.skillsgateway.server.approval.NameCollisionGate;
import dev.skillsgateway.server.approval.PluginInventoryUnavailableException;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.vetting.Waiver;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The name-collision precondition on the approval gate (GW_APPROVAL_0019, GW_APPROVAL_0020,
 * GW_APPROVAL_0021): refusal, scope, first-come-wins, restore, waivers, the override and the ledger.
 * Concurrency is {@link NameCollisionRaceTests}.
 */
class NameCollisionTests extends AbstractNameCollisionTest {

    @Autowired
    private GitStorage storage;

    @Autowired
    private WaiverService waiverService;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private NameCollisionGate nameCollisionGate;

    private static final Instant NEXT_MONTH = Instant.now().plus(30, ChronoUnit.DAYS);

    // ---- GW_APPROVAL_0019: the refusal ------------------------------------------------------------

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019"})
    void a_lookalike_of_an_approved_plugin_is_refused_naming_the_incumbent_and_nothing_is_published() throws Exception {
        String name = uniquePlugin();
        Registered incumbent = approved(name);
        Registered newcomer = held("unrelated-" + uniquePlugin(), lookalike(name));

        mockMvc.perform(post(
                                "/api/v1/snapshots/{id}/approve",
                                newcomer.snapshot().id())
                        .with(oidcLogin().idToken(token -> token.subject("alice"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ruleId").value(NameCollisionGate.RULE_ID))
                .andExpect(jsonPath("$.collisions.length()").value(1))
                .andExpect(jsonPath("$.collisions[0].pluginName").value(lookalike(name)))
                .andExpect(jsonPath("$.collisions[0].location").value(".claude-plugin/marketplace.json:6"))
                .andExpect(jsonPath("$.collisions[0].incumbents[0].marketplace")
                        .value(incumbent.marketplace().name()))
                .andExpect(jsonPath("$.collisions[0].incumbents[0].snapshotId")
                        .value(incumbent.snapshot().id()))
                .andExpect(jsonPath("$.collisions[0].incumbents[0].pluginName").value(name));

        assertThat(state(newcomer)).isEqualTo(Snapshot.HELD);
        assertThat(storage.publishedIfServing(newcomer.marketplace().name())).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019"})
    void every_lookalike_spelling_is_refused_through_the_service_too() throws Exception {
        String name = uniquePlugin();
        approved(name);
        for (String spelling : List.of(
                name.toUpperCase(java.util.Locale.ROOT),
                name.replace("-", "_"),
                name.replace("-", ""),
                name.replace("o", "0"))) {
            Registered newcomer = held(spelling);
            assertThatThrownBy(() -> approvalService.approve(newcomer.snapshot().id(), "alice"))
                    .as(spelling)
                    .isInstanceOf(NameCollisionException.class);
            assertThat(state(newcomer)).isEqualTo(Snapshot.HELD);
        }
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019"})
    void a_snapshot_whose_plugin_names_cannot_be_read_is_refused_without_naming_a_collision() throws Exception {
        Registered unreadable = held(uniquePlugin());
        // Take the record ingestion wrote away and the manifest with it: nothing can index the names.
        jdbc.sql("DELETE FROM snapshot_facts WHERE snapshot_id = :id")
                .param("id", unreadable.snapshot().id())
                .update();
        jdbc.sql("UPDATE snapshots SET sha = :sha WHERE id = :id")
                .param("sha", "0".repeat(40))
                .param("id", unreadable.snapshot().id())
                .update();
        try {
            // The gate itself, since the closure-completeness gate ahead of it also reads the commit
            // and refuses first on the approval path.
            Snapshot broken =
                    snapshotRepository.findById(unreadable.snapshot().id()).orElseThrow();
            assertThatThrownBy(() -> nameCollisionGate.require(broken, unreadable.marketplace()))
                    .isInstanceOf(PluginInventoryUnavailableException.class)
                    .isNotInstanceOf(NameCollisionException.class)
                    .hasMessageNotContaining("collides");
            assertThat(nameCollisionGate
                            .evaluate(broken, unreadable.marketplace())
                            .refused())
                    .isTrue();
            // And the in-transaction evaluation, which must not trust an index that is not there.
            assertThatThrownBy(() -> nameCollisionGate.guarded(broken, () -> {
                        throw new AssertionError("the transition must not run");
                    }))
                    .isInstanceOf(PluginInventoryUnavailableException.class);
            assertThat(state(unreadable)).isEqualTo(Snapshot.HELD);
        } finally {
            jdbc.sql("UPDATE snapshots SET sha = :sha WHERE id = :id")
                    .param("sha", unreadable.snapshot().sha())
                    .param("id", unreadable.snapshot().id())
                    .update();
        }
    }

    // ---- GW_APPROVAL_0019.2: scope and first-come-wins -------------------------------------------

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019.2"})
    void a_distinct_name_and_a_shared_skill_name_pass() throws Exception {
        approved(uniquePlugin());
        // Every fixture's skill is called "hello" in every plugin: skill names are never compared.
        Registered distinct = held(uniquePlugin());
        assertThat(approve(distinct.snapshot().id()).state()).isEqualTo(Snapshot.APPROVED);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019.2"})
    void a_name_the_marketplace_already_carries_is_not_checked_again_and_the_incumbent_stays_served() throws Exception {
        String name = uniquePlugin();
        Registered incumbent = approved(name);
        Path upstream = createUpstream(manifest(lookalike(name)));
        Registered newcomer = registerAndIngest(uniqueName("nc"), upstream);

        assertThatThrownBy(() -> approvalService.approve(newcomer.snapshot().id(), "alice"))
                .isInstanceOf(NameCollisionException.class);
        waiverService.create(
                newcomer.snapshot().id(),
                NameCollisionGate.RULE_ID,
                WaiverScope.SNAPSHOT,
                null,
                "a sanctioned fork",
                NEXT_MONTH,
                "root");
        assertThat(approve(newcomer.snapshot().id()).state()).isEqualTo(Snapshot.APPROVED);

        // The next snapshot of the newcomer carries the same name: raised and accepted once, not again.
        addUpstreamCommit(upstream, "second");
        Snapshot second = ingestionService.ingest(newcomer.marketplace(), null);
        assertThat(approvalService.nameCollisions(second.id()).orElseThrow().collisions())
                .isEmpty();
        assertThat(approve(second.id()).state()).isEqualTo(Snapshot.APPROVED);

        // And the incumbent was never touched by any of it.
        assertThat(state(incumbent)).isEqualTo(Snapshot.APPROVED);
        assertThat(storage.publishedIfServing(incumbent.marketplace().name())).isPresent();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019.2"})
    void a_later_snapshot_of_the_incumbent_is_not_flagged_by_the_newcomer_it_admitted() throws Exception {
        String name = uniquePlugin();
        Path incumbentUpstream = createUpstream(manifest(name));
        Registered incumbent = registerAndIngest(uniqueName("nc"), incumbentUpstream);
        approve(incumbent.snapshot().id());
        Registered newcomer = held(lookalike(name));
        waiverService.create(
                newcomer.snapshot().id(),
                NameCollisionGate.RULE_ID,
                WaiverScope.SNAPSHOT,
                null,
                "a sanctioned fork",
                NEXT_MONTH,
                "root");
        approve(newcomer.snapshot().id());

        addUpstreamCommit(incumbentUpstream, "next");
        Snapshot next = ingestionService.ingest(incumbent.marketplace(), null);
        assertThat(approve(next.id()).state()).isEqualTo(Snapshot.APPROVED);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019.2"})
    void a_plugin_named_after_another_marketplaces_skill_passes() throws Exception {
        // The shared fixture's skill directory is "hello" in every marketplace; a plugin called
        // something unique whose skill is also "hello" must pass even though "hello" is everywhere.
        Registered first = approved(uniquePlugin());
        Registered second = held(uniquePlugin());
        assertThat(approvalService
                        .nameCollisions(second.snapshot().id())
                        .orElseThrow()
                        .collisions())
                .isEmpty();
        assertThat(first.snapshot().state()).isEqualTo(Snapshot.APPROVED);
    }

    // ---- GW_APPROVAL_0019.4: restore -------------------------------------------------------------

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019.4"})
    void restoring_a_revoked_snapshot_whose_name_was_taken_meanwhile_is_refused_until_waived() throws Exception {
        String name = uniquePlugin();
        Registered original = approved(name);
        snapshotRepository.revoke(original.snapshot().id(), "revet-policy", "test", Snapshot.REVOKED_BY_REVET);
        // While it is revoked another marketplace takes the name: nothing approved carries it.
        approved(lookalike(name));

        assertThatThrownBy(() -> approvalService.approve(original.snapshot().id(), "alice"))
                .isInstanceOf(NameCollisionException.class);
        assertThat(state(original)).isEqualTo(Snapshot.REVOKED);

        waiverService.create(
                original.snapshot().id(),
                NameCollisionGate.RULE_ID,
                WaiverScope.SNAPSHOT,
                null,
                "restoring the original",
                NEXT_MONTH,
                "root");
        assertThat(approve(original.snapshot().id()).state()).isEqualTo(Snapshot.APPROVED);
    }

    // ---- GW_APPROVAL_0020: only a covering waiver lifts it ---------------------------------------

    @Test
    @SVCs({"SVC_GW_APPROVAL_0020"})
    void only_an_active_covering_waiver_lifts_a_collision_and_the_vetting_override_does_not() throws Exception {
        String name = uniquePlugin();
        approved(name);
        Registered newcomer = held(lookalike(name));
        long id = newcomer.snapshot().id();

        // A waiver on another rule.
        waiverService.create(id, "aws-access-key-id", WaiverScope.SNAPSHOT, null, "unrelated", NEXT_MONTH, "root");
        assertThatThrownBy(() -> approvalService.approve(id, "alice")).isInstanceOf(NameCollisionException.class);

        // A waiver on the rule, at a path that does not cover the manifest.
        waiverService.create(
                id, NameCollisionGate.RULE_ID, WaiverScope.PATH, "plugins", "wrong path", NEXT_MONTH, "root");
        assertThatThrownBy(() -> approvalService.approve(id, "alice")).isInstanceOf(NameCollisionException.class);

        // An expired one.
        Waiver expired = waiverService.create(
                id, NameCollisionGate.RULE_ID, WaiverScope.SNAPSHOT, null, "lapsed", NEXT_MONTH, "root");
        jdbc.sql("UPDATE vetting_waivers SET expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", expired.id())
                .update();
        assertThatThrownBy(() -> approvalService.approve(id, "alice")).isInstanceOf(NameCollisionException.class);

        // A revoked one.
        Waiver revoked = waiverService.create(
                id, NameCollisionGate.RULE_ID, WaiverScope.SNAPSHOT, null, "withdrawn", NEXT_MONTH, "root");
        waiverService.revoke(revoked.id(), "root");
        assertThatThrownBy(() -> approvalService.approve(id, "alice")).isInstanceOf(NameCollisionException.class);

        // The administrator's override of a blocked vetting outcome is not an acceptance of this.
        assertThatThrownBy(() -> approvalService.approve(
                        id, "root", ApprovalService.ApprovalOverride.ofVettingFailure("override everything")))
                .isInstanceOf(NameCollisionException.class);
        assertThat(state(newcomer)).isEqualTo(Snapshot.HELD);

        // An active snapshot-scoped waiver on the rule does.
        Waiver accepted = waiverService.create(
                id, NameCollisionGate.RULE_ID, WaiverScope.SNAPSHOT, null, "a sanctioned fork", NEXT_MONTH, "root");
        ApprovalService.Approved result = approvalService.approve(id, "alice");
        assertThat(result.snapshot().state()).isEqualTo(Snapshot.APPROVED);
        assertThat(result.waiversApplied()).anySatisfy(suppression -> {
            assertThat(suppression.waiverId()).isEqualTo(accepted.id());
            assertThat(suppression.ruleId()).isEqualTo(NameCollisionGate.RULE_ID);
            assertThat(suppression.location()).isEqualTo(".claude-plugin/marketplace.json:5");
        });
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0020"})
    void a_waiver_the_reviewer_wrote_is_a_four_eyes_conflict_and_its_use_reaches_the_ledger() throws Exception {
        String name = uniquePlugin();
        approved(name);
        Registered newcomer = held(lookalike(name));
        long id = newcomer.snapshot().id();
        Waiver own = waiverService.create(
                id, NameCollisionGate.RULE_ID, WaiverScope.SNAPSHOT, null, "my own fork", NEXT_MONTH, "alice");

        assertThat(approvalService.fourEyes(id, "alice").orElseThrow().conflicts())
                .anySatisfy(conflict -> assertThat(conflict.role()).isEqualTo(FourEyesGate.ROLE_WAIVER_AUTHOR));

        mockMvc.perform(post("/api/v1/snapshots/{id}/approve", id)
                        .with(oidcLogin().idToken(token -> token.subject("alice"))))
                .andExpect(status().isOk());
        assertThat(ledger(newcomer, WaiverService.EVENT_APPLIED)).anySatisfy(detail -> assertThat(detail)
                .contains("waiver=" + own.id())
                .contains("rule=" + NameCollisionGate.RULE_ID));
        assertThat(ledger(newcomer, FourEyesGate.EVENT_CONFLICT)).isNotEmpty();
    }

    // ---- GW_APPROVAL_0021: the report and the ledger ---------------------------------------------

    @Test
    @SVCs({"SVC_GW_APPROVAL_0021"})
    void the_report_agrees_with_the_gate_and_the_refusal_is_on_the_ledger() throws Exception {
        String name = uniquePlugin();
        Registered incumbent = approved(name);
        Registered colliding = held(lookalike(name));
        Registered clean = held(uniquePlugin());

        mockMvc.perform(get(
                                "/api/v1/snapshots/{id}/name-collisions",
                                colliding.snapshot().id())
                        .with(oidcLogin().idToken(token -> token.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.inventoryAvailable").value(true))
                .andExpect(jsonPath("$.refused").value(true))
                .andExpect(jsonPath("$.collisions[0].pluginName").value(lookalike(name)))
                .andExpect(jsonPath("$.collisions[0].covered").value(false))
                .andExpect(jsonPath("$.collisions[0].finding.id").value(NameCollisionGate.RULE_ID))
                .andExpect(jsonPath("$.collisions[0].finding.location").value(".claude-plugin/marketplace.json:5"))
                .andExpect(jsonPath("$.collisions[0].incumbents[0].marketplace")
                        .value(incumbent.marketplace().name()));
        mockMvc.perform(get(
                                "/api/v1/snapshots/{id}/name-collisions",
                                clean.snapshot().id())
                        .with(oidcLogin().idToken(token -> token.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").value(false))
                .andExpect(jsonPath("$.collisions.length()").value(0));

        assertThatThrownBy(() -> approvalService.approve(colliding.snapshot().id(), "alice"))
                .isInstanceOf(NameCollisionException.class);
        assertThat(ledger(colliding, "snapshot-approval-refused")).anySatisfy(detail -> assertThat(detail)
                .startsWith(NameCollisionGate.RULE_ID + ":")
                .contains(incumbent.marketplace().name()));

        mockMvc.perform(post(
                                "/api/v1/snapshots/{id}/waivers",
                                colliding.snapshot().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleId": "%s", "scope": "snapshot",
                                 "justification": "a sanctioned fork", "expiresAt": "%s"}
                                """.formatted(NameCollisionGate.RULE_ID, NEXT_MONTH))
                        .with(oidcLogin().idToken(token -> token.subject("root"))))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(get(
                                "/api/v1/snapshots/{id}/name-collisions",
                                colliding.snapshot().id())
                        .with(oidcLogin().idToken(token -> token.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").value(false))
                .andExpect(jsonPath("$.collisions[0].covered").value(true))
                .andExpect(jsonPath("$.collisions[0].waiver.approvedBy").value("root"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0021"})
    void the_report_for_an_unknown_snapshot_is_not_found() throws Exception {
        mockMvc.perform(get("/api/v1/snapshots/{id}/name-collisions", Long.MAX_VALUE)
                        .with(oidcLogin().idToken(token -> token.subject("alice"))))
                .andExpect(status().isNotFound());
    }

    private String state(Registered registered) {
        return snapshotRepository
                .findById(registered.snapshot().id())
                .orElseThrow()
                .state();
    }

    private List<String> ledger(Registered registered, String event) {
        return fetchLogRepository.list().stream()
                .filter(row -> event.equals(row.get("event")))
                .filter(row -> registered.marketplace().name().equals(row.get("marketplace")))
                .map(row -> String.valueOf(row.get("detail")))
                .toList();
    }
}

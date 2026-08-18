package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.admin.MarketplaceRegistrationService;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredAuditSink;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredGrant;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredMarketplace;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredPolicyRule;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredWebhook;
import dev.skillsgateway.server.config.SkillsGatewayProperties.Estate;
import dev.skillsgateway.server.estate.EstateReconciler;
import dev.skillsgateway.server.estate.EstateReconciliation;
import dev.skillsgateway.server.persistence.AuditSink;
import dev.skillsgateway.server.persistence.AuditSinkRepository;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.WebhookSubscriber;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import dev.skillsgateway.server.policy.PolicyRule;
import dev.skillsgateway.server.policy.PolicyRuleService;
import dev.skillsgateway.server.roles.RoleGrant;
import dev.skillsgateway.server.roles.RoleGrantRepository;
import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The declarative estate (GW_0083–GW_0087) in its own context — and therefore its own database —
 * with role enforcement enabled and an estate declared in configuration, so the startup
 * reconciliation itself is under test. The declared objects (estate-alpha, estate-hook,
 * estate-siem, the two grants) are never mutated by any test here: the converged-no-op test
 * depends on the declaration staying converged. Drift and rotation scenarios reconcile
 * programmatic estates under their own names instead.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.roles.enabled=true",
            "skills-gateway.roles.admins=root",
            // The declared webhook secret references this property: the operator's env-var
            // indirection, proven end to end through Spring's placeholder resolution.
            "test.estate.hook-secret=hook-secret-0123456789abcdef",
            "skills-gateway.estate.marketplaces[0].name=estate-alpha",
            "skills-gateway.estate.marketplaces[0].url=file:///tmp/estate-alpha-upstream",
            "skills-gateway.estate.marketplaces[0].sync-mode=scheduled",
            "skills-gateway.estate.grants[0].principal=estate-approver",
            "skills-gateway.estate.grants[0].role=approver",
            "skills-gateway.estate.grants[0].marketplace=estate-alpha",
            "skills-gateway.estate.grants[1].principal=estate-auditor",
            "skills-gateway.estate.grants[1].role=auditor",
            "skills-gateway.estate.webhooks[0].name=estate-hook",
            "skills-gateway.estate.webhooks[0].url=https://receiver.invalid/hook",
            "skills-gateway.estate.webhooks[0].events=snapshot.approved",
            "skills-gateway.estate.webhooks[0].secret=${test.estate.hook-secret}",
            "skills-gateway.estate.audit-sinks[0].name=estate-siem",
            "skills-gateway.estate.audit-sinks[0].url=https://siem.invalid/ingest",
            "skills-gateway.estate.audit-sinks[0].secret=sink-secret-0123456789abcdef",
            "skills-gateway.estate.audit-sinks[0].batch-size=100",
            "skills-gateway.estate.policy-rules[0].name=estate-no-shell",
            "skills-gateway.estate.policy-rules[0].description=deny skills declaring shell tools",
            "skills-gateway.estate.policy-rules[0].expression=skills.exists(s, s.tools.exists(t,"
                    + " t.startsWith(\"Bash\"))) && snapshot.marketplace == \"estate-alpha\""
        })
class EstateReconciliationTests extends AbstractGatewayTest {

    private static final String HOOK_SECRET = "hook-secret-0123456789abcdef";
    private static final String SINK_SECRET = "sink-secret-0123456789abcdef";

    @Autowired
    private EstateReconciler reconciler;

    @Autowired
    private SkillsGatewayProperties properties;

    @Autowired
    private MarketplaceRegistrationService registrationService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private RoleGrantRepository roleGrantRepository;

    @Autowired
    private WebhookSubscriberRepository subscriberRepository;

    @Autowired
    private AuditSinkRepository sinkRepository;

    @Autowired
    private PolicyRuleService policyRuleService;

    @Test
    @SVCs({"SVC_GW_0083"})
    void startup_reconciliation_applies_the_declared_estate_through_the_audited_paths() {
        Marketplace alpha = marketplaceRepository.findByName("estate-alpha").orElseThrow();
        assertThat(alpha.url()).isEqualTo("file:///tmp/estate-alpha-upstream");
        assertThat(alpha.syncMode()).isEqualTo("scheduled");

        assertThat(roleService.rolesOf("estate-approver"))
                .contains(new RoleService.EffectiveRole("approver", "estate-alpha"));
        assertThat(roleService.rolesOf("estate-auditor")).contains(new RoleService.EffectiveRole("auditor", null));

        WebhookSubscriber hook = subscriberRepository.findByName("estate-hook").orElseThrow();
        assertThat(hook.url()).isEqualTo("https://receiver.invalid/hook");
        assertThat(hook.events()).isEqualTo("snapshot.approved");
        // The declared ${test.estate.hook-secret} placeholder resolved to the operator's value.
        assertThat(hook.secret()).isEqualTo(HOOK_SECRET);

        AuditSink siem = sinkRepository.findByName("estate-siem").orElseThrow();
        assertThat(siem.batchSize()).isEqualTo(100);
        assertThat(siem.cursorPosition()).isZero();
        assertThat(subscriberRepository
                        .findById(siem.subscriberId())
                        .orElseThrow()
                        .secret())
                .isEqualTo(SINK_SECRET);

        // Every applied change is on the ledger under its API event name with the reconciler actor.
        assertThat(ledger("marketplace-registered", EstateReconciler.ACTOR))
                .anySatisfy(entry -> assertThat(entry.get("marketplace")).isEqualTo("estate-alpha"));
        assertThat(ledger("sync-mode-changed", EstateReconciler.ACTOR))
                .anySatisfy(entry -> assertThat(entry.get("detail")).isEqualTo("scheduled"));
        assertThat(ledger("role-granted", EstateReconciler.ACTOR))
                .anySatisfy(entry -> assertThat(String.valueOf(entry.get("detail")))
                        .isEqualTo("principal=estate-approver role=approver"));
        assertThat(ledger("webhook-subscriber-created", EstateReconciler.ACTOR)).isNotEmpty();
        assertThat(ledger("audit-sink-created", EstateReconciler.ACTOR)).isNotEmpty();

        // The declared policy rule was created through the compiled, audited path and is enabled.
        PolicyRule declaredRule = policyRuleService.find("estate-no-shell").orElseThrow();
        assertThat(declaredRule.enabled()).isTrue();
        assertThat(declaredRule.createdBy()).isEqualTo(EstateReconciler.ACTOR);
        assertThat(ledger("policy-rule-created", EstateReconciler.ACTOR))
                .anySatisfy(
                        entry -> assertThat(String.valueOf(entry.get("detail"))).contains("rule=estate-no-shell"));
        // (The startup run's own report — trigger and counts — is asserted in
        // EstateStartupFailureTests, whose context runs nothing after startup; here later tests
        // legitimately overwrite the in-memory last run.)
    }

    @Test
    @SVCs({"SVC_GW_0083"})
    void a_converged_estate_reconciles_with_zero_writes_and_zero_ledger_entries() throws Exception {
        WebhookSubscriber hookBefore =
                subscriberRepository.findByName("estate-hook").orElseThrow();
        AuditSink siemBefore = sinkRepository.findByName("estate-siem").orElseThrow();
        long ledgerHead = fetchLogRepository.maxId();

        EstateReconciliation report = reconciler.reconcile(properties.estate(), "api");

        assertThat(report.created()).isZero();
        assertThat(report.updated()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.unchanged()).isEqualTo(6);
        assertThat(fetchLogRepository.maxId())
                .as("a no-op reconcile appends nothing to the ledger")
                .isEqualTo(ledgerHead);
        assertThat(subscriberRepository.findByName("estate-hook").orElseThrow()).isEqualTo(hookBefore);
        assertThat(sinkRepository.findByName("estate-siem").orElseThrow()).isEqualTo(siemBefore);

        // The on-demand trigger is an admin action and is itself recorded — exactly one entry.
        var root = oidcLogin().idToken(token -> token.subject("root"));
        mockMvc.perform(post("/api/estate/reconcile").with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trigger").value("api"))
                .andExpect(jsonPath("$.failed").value(0));
        assertThat(fetchLogRepository.maxId()).isEqualTo(ledgerHead + 1);
        assertThat(ledger("estate-reconcile-triggered", "root")).isNotEmpty();
    }

    @Test
    @SVCs({"SVC_GW_0083"})
    void objects_absent_from_the_declaration_are_never_touched() {
        String survivor = uniqueName("estate-survivor");
        registrationService.register(survivor, "https://example.invalid/" + survivor + ".git", "alice");
        RoleGrant grant = roleService.grant("estate-survivor-user", "approver", survivor, "alice");

        EstateReconciliation report = reconciler.reconcile(properties.estate(), "api");

        assertThat(report.entries()).noneMatch(entry -> survivor.equals(entry.name()));
        assertThat(marketplaceRepository.findByName(survivor)).isPresent();
        assertThat(roleGrantRepository.findById(grant.id())).isPresent();
    }

    @Test
    @SVCs({"SVC_GW_0084"})
    void declared_marketplaces_face_the_same_registration_gate_as_the_api() {
        String drift = uniqueName("estate-drift");
        registrationService.register(drift, "https://example.invalid/original.git", "alice");
        long ledgerHead = fetchLogRepository.maxId();

        String fresh = uniqueName("estate-fresh");
        Estate estate = new Estate(
                List.of(
                        new DeclaredMarketplace("estate-evil", "ssh://evil.invalid/repo.git", null),
                        new DeclaredMarketplace("catalog", "https://example.invalid/catalog.git", null),
                        new DeclaredMarketplace(drift, "https://example.invalid/other.git", null),
                        new DeclaredMarketplace("estate-webhookmode", "https://example.invalid/wh.git", "webhook"),
                        new DeclaredMarketplace(fresh, "https://example.invalid/fresh.git", "scheduled")),
                null,
                null,
                null,
                null);

        EstateReconciliation report = reconciler.reconcile(estate, "api");

        assertThat(actionOf(report, "estate-evil")).isEqualTo("failed");
        assertThat(actionOf(report, "catalog")).isEqualTo("failed");
        assertThat(actionOf(report, drift)).isEqualTo("failed");
        assertThat(actionOf(report, "estate-webhookmode")).isEqualTo("failed");
        assertThat(actionOf(report, fresh)).isEqualTo("created");

        assertThat(marketplaceRepository.findByName("estate-evil")).isEmpty();
        assertThat(marketplaceRepository.findByName("catalog")).isEmpty();
        assertThat(marketplaceRepository.findByName("estate-webhookmode")).isEmpty();
        assertThat(marketplaceRepository.findByName(drift).orElseThrow().url())
                .as("a declared URL never rewrites a registered upstream")
                .isEqualTo("https://example.invalid/original.git");
        Marketplace created = marketplaceRepository.findByName(fresh).orElseThrow();
        assertThat(created.syncMode()).isEqualTo("scheduled");

        // Each failure is loud on the ledger; none of them wrote anything else there.
        List<Map<String, Object>> failures = ledger("estate-reconciliation-failed", EstateReconciler.ACTOR);
        assertThat(failures).anySatisfy(entry -> assertThat(String.valueOf(entry.get("detail")))
                .contains("estate-evil"));
        assertThat(failures).anySatisfy(entry -> assertThat(String.valueOf(entry.get("detail")))
                .contains("estate-webhookmode"));
        assertThat(fetchLogRepository.list().stream()
                        .filter(entry -> ((Number) entry.get("id")).longValue() > ledgerHead)
                        .filter(entry -> "marketplace-registered".equals(entry.get("event"))))
                .hasSize(1);
    }

    @Test
    @SVCs({"SVC_GW_0085"})
    void declared_grants_reconcile_through_the_audited_grant_path() throws Exception {
        // The startup grant took effect under enforcement: the approver reaches its marketplace's
        // ingest (which then fails upstream, 502 — authorization passed), a stranger gets 403.
        var approver = oidcLogin().idToken(token -> token.subject("estate-approver"));
        var mallory = oidcLogin().idToken(token -> token.subject("mallory"));
        mockMvc.perform(post("/api/marketplaces/estate-alpha/ingest").with(mallory))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/marketplaces/estate-alpha/ingest").with(approver))
                .andExpect(status().isBadGateway());

        // A grant may reference an API-registered marketplace; an unknown one fails in isolation.
        String apiSide = uniqueName("estate-apiside");
        registrationService.register(apiSide, "https://example.invalid/" + apiSide + ".git", "alice");
        Estate estate = new Estate(
                null,
                List.of(
                        new DeclaredGrant("estate-late", "approver", apiSide),
                        new DeclaredGrant("estate-orphan", "approver", "estate-no-such-marketplace")),
                null,
                null,
                null);

        EstateReconciliation report = reconciler.reconcile(estate, "api");
        assertThat(report.created()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(roleService.rolesOf("estate-late")).contains(new RoleService.EffectiveRole("approver", apiSide));
        assertThat(roleService.rolesOf("estate-orphan")).isEmpty();

        // Idempotent: the same declaration grants nothing twice.
        EstateReconciliation again = reconciler.reconcile(estate, "api");
        assertThat(again.created()).isZero();
        assertThat(roleService.rolesOf("estate-late")).hasSize(1);
    }

    @Test
    @SVCs({"SVC_GW_0086"})
    void declared_secrets_are_write_only_rotated_idempotently_and_floored() throws Exception {
        String first = "estate-rotate-secret-one-0123456789";
        String second = "estate-rotate-secret-two-0123456789";
        Estate v1 = new Estate(
                null,
                null,
                List.of(new DeclaredWebhook("estate-rotate", "https://rotate.invalid/hook", null, first)),
                List.of(new DeclaredAuditSink("estate-rotate-sink", "https://rotate.invalid/sink", first, 5L, 100)),
                null);
        reconciler.reconcile(v1, "api");
        WebhookSubscriber hook =
                subscriberRepository.findByName("estate-rotate").orElseThrow();
        assertThat(hook.secret()).isEqualTo(first);
        AuditSink sink = sinkRepository.findByName("estate-rotate-sink").orElseThrow();
        assertThat(sink.cursorPosition()).isEqualTo(5);

        // A changed referenced value rotates the stored secret; the ledger records it, valueless.
        Estate v2 = new Estate(
                null,
                null,
                List.of(new DeclaredWebhook("estate-rotate", "https://rotate.invalid/hook", null, second)),
                List.of(new DeclaredAuditSink("estate-rotate-sink", "https://rotate.invalid/sink", second, 999L, 200)),
                null);
        EstateReconciliation rotated = reconciler.reconcile(v2, "api");
        assertThat(rotated.updated()).isEqualTo(2);
        assertThat(subscriberRepository
                        .findByName("estate-rotate")
                        .orElseThrow()
                        .secret())
                .isEqualTo(second);
        AuditSink sinkAfter = sinkRepository.findByName("estate-rotate-sink").orElseThrow();
        assertThat(subscriberRepository
                        .findById(sinkAfter.subscriberId())
                        .orElseThrow()
                        .secret())
                .isEqualTo(second);
        assertThat(sinkAfter.batchSize()).isEqualTo(200);
        assertThat(sinkAfter.cursorPosition())
                .as("the cursor is runtime progress: set at creation, never reconciled")
                .isEqualTo(5);
        assertThat(ledger("webhook-subscriber-updated", EstateReconciler.ACTOR)).isNotEmpty();
        assertThat(ledger("audit-sink-updated", EstateReconciler.ACTOR)).isNotEmpty();

        // The same declaration again writes nothing.
        long ledgerHead = fetchLogRepository.maxId();
        EstateReconciliation converged = reconciler.reconcile(v2, "api");
        assertThat(converged.updated()).isZero();
        assertThat(fetchLogRepository.maxId()).isEqualTo(ledgerHead);

        // Blank and too-short secrets are refused before anything is written.
        Estate weak = new Estate(
                null,
                null,
                List.of(
                        new DeclaredWebhook("estate-weak", "https://rotate.invalid/hook", null, " "),
                        new DeclaredWebhook("estate-short", "https://rotate.invalid/hook", null, "tooshort")),
                List.of(new DeclaredAuditSink("estate-weak-sink", "https://rotate.invalid/sink", "short", null, null)),
                null);
        EstateReconciliation refused = reconciler.reconcile(weak, "api");
        assertThat(refused.failed()).isEqualTo(3);
        assertThat(subscriberRepository.findByName("estate-weak")).isEmpty();
        assertThat(subscriberRepository.findByName("estate-short")).isEmpty();
        assertThat(sinkRepository.findByName("estate-weak-sink")).isEmpty();

        // No secret value ever reaches the ledger or the report.
        assertThat(fetchLogRepository.list().toString()).doesNotContain(first).doesNotContain(second);
        assertThat(rotated.toString()).doesNotContain(first).doesNotContain(second);
        String reportJson = mockMvc.perform(
                        get("/api/estate").with(oidcLogin().idToken(token -> token.subject("root"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(reportJson).doesNotContain(first).doesNotContain(second).doesNotContain(HOOK_SECRET);
    }

    @Test
    @SVCs({"SVC_GW_0087"})
    void a_failing_entry_is_isolated_and_the_report_is_answerable_by_role() throws Exception {
        String good = uniqueName("estate-isolated");
        Estate estate = new Estate(
                List.of(
                        new DeclaredMarketplace("estate-broken", "ssh://evil.invalid/repo.git", null),
                        new DeclaredMarketplace(good, "https://example.invalid/good.git", null)),
                null,
                null,
                null,
                null);
        EstateReconciliation report = reconciler.reconcile(estate, "api");
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.created()).isEqualTo(1);
        assertThat(marketplaceRepository.findByName(good)).isPresent();
        assertThat(entryOf(report, "estate-broken").detail()).contains("scheme");

        // The report endpoint answers auditors and admins and refuses roleless sessions; the
        // trigger endpoint refuses non-admins (the full walk lives in RoleEnforcementTests).
        var auditor = oidcLogin().idToken(token -> token.subject("estate-auditor"));
        var mallory = oidcLogin().idToken(token -> token.subject("mallory"));
        mockMvc.perform(get("/api/estate").with(auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray());
        mockMvc.perform(get("/api/estate").with(mallory)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/estate/reconcile").with(auditor)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/estate/reconcile").with(mallory)).andExpect(status().isForbidden());
    }

    @Test
    @SVCs({"SVC_GW_0089"})
    void declared_policy_rules_reconcile_through_the_compiled_audited_path() {
        String fresh = uniqueName("estate-rule");
        String broken = uniqueName("estate-rule-broken");
        Estate estate = new Estate(
                null,
                null,
                null,
                null,
                List.of(
                        new DeclaredPolicyRule(fresh, "fixture", "snapshot.marketplace == \"nowhere\"", null),
                        new DeclaredPolicyRule(broken, "fixture", "skills.exists(s,", null)));

        EstateReconciliation report = reconciler.reconcile(estate, "api");
        assertThat(actionOf(report, fresh)).isEqualTo("created");
        assertThat(actionOf(report, broken)).isEqualTo("failed");
        assertThat(entryOf(report, broken).detail()).contains("compile");
        assertThat(policyRuleService.find(fresh)).isPresent();
        assertThat(policyRuleService.find(broken))
                .as("a non-compiling declared expression is never stored")
                .isEmpty();

        // Converged: the identical declaration writes nothing.
        Estate converged = new Estate(
                null,
                null,
                null,
                null,
                List.of(new DeclaredPolicyRule(fresh, "fixture", "snapshot.marketplace == \"nowhere\"", null)));
        EstateReconciliation again = reconciler.reconcile(converged, "api");
        assertThat(actionOf(again, fresh)).isEqualTo("unchanged");

        // Drift converges through the audited update path.
        Estate drifted = new Estate(
                null,
                null,
                null,
                null,
                List.of(new DeclaredPolicyRule(fresh, "fixture", "snapshot.marketplace == \"elsewhere\"", false)));
        EstateReconciliation updated = reconciler.reconcile(drifted, "api");
        assertThat(actionOf(updated, fresh)).isEqualTo("updated");
        PolicyRule stored = policyRuleService.find(fresh).orElseThrow();
        assertThat(stored.expression()).isEqualTo("snapshot.marketplace == \"elsewhere\"");
        assertThat(stored.enabled()).isFalse();
        assertThat(ledger("policy-rule-updated", EstateReconciler.ACTOR))
                .anySatisfy(
                        entry -> assertThat(String.valueOf(entry.get("detail"))).contains("rule=" + fresh));
    }

    // ---------------------------------------------------------------- helpers

    private String actionOf(EstateReconciliation report, String name) {
        return entryOf(report, name).action();
    }

    private EstateReconciliation.Entry entryOf(EstateReconciliation report, String name) {
        return report.entries().stream()
                .filter(entry -> name.equals(entry.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no report entry named " + name));
    }

    private List<Map<String, Object>> ledger(String event, String actor) {
        return fetchLogRepository.list().stream()
                .filter(entry -> event.equals(entry.get("event")))
                .filter(entry -> actor.equals(entry.get("principal")))
                .toList();
    }
}

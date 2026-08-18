package dev.skillsgateway.server.policy;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The fail-closed policy gate (GW_0090): every enabled rule, evaluated over freshly built facts at
 * the moment of approval. A rule that evaluates true denies; a rule that errors denies; facts
 * that cannot be built deny every enabled rule — an attacker who can provoke an evaluation error
 * must not thereby switch a rule off. Every denial lands on the append-only ledger (GW_0091)
 * before the refusal propagates, so the decision is auditable even though nothing was approved.
 */
@Service
public class PolicyGate {

    public static final String EVENT_DENIED = "policy-denied";

    private final PolicyRuleRepository repository;
    private final SnapshotFactsService factsService;
    private final AdminAuditLogger auditLogger;

    public PolicyGate(
            PolicyRuleRepository repository, SnapshotFactsService factsService, AdminAuditLogger auditLogger) {
        this.repository = repository;
        this.factsService = factsService;
        this.auditLogger = auditLogger;
    }

    /**
     * Refuses the approval when any enabled rule decides against it; returns quietly when none
     * does. With no enabled rules this is a no-op — facts are only built when a rule will read
     * them. All deciding rules are reported at once, the vetting gate's "see everything wrong at
     * once" principle.
     */
    @Requirements({"GW_0090", "GW_0091"})
    public void enforce(Snapshot snapshot, Marketplace marketplace, String reviewer) {
        List<PolicyRule> enabled = repository.listEnabled();
        if (enabled.isEmpty()) {
            return;
        }
        Map<String, Object> facts = null;
        String factsError = null;
        try {
            facts = factsService.build(snapshot, marketplace);
        } catch (PolicyEvaluationException e) {
            factsError = e.getMessage();
        }
        List<PolicyDeniedException.Denial> denials = new ArrayList<>();
        for (PolicyRule rule : enabled) {
            if (factsError != null) {
                denials.add(new PolicyDeniedException.Denial(rule.name(), "error: " + factsError));
                continue;
            }
            try {
                if (CelPolicy.matches(CelPolicy.compile(rule.expression()), facts)) {
                    denials.add(new PolicyDeniedException.Denial(rule.name(), "matched"));
                }
            } catch (PolicyExpressionException | PolicyEvaluationException e) {
                denials.add(new PolicyDeniedException.Denial(rule.name(), "error: " + e.getMessage()));
            }
        }
        if (denials.isEmpty()) {
            return;
        }
        for (PolicyDeniedException.Denial denial : denials) {
            auditLogger.record(
                    reviewer,
                    marketplace.name(),
                    EVENT_DENIED,
                    snapshot.sha(),
                    "rule=%s outcome=%s".formatted(denial.rule(), denial.outcome()));
        }
        throw new PolicyDeniedException(snapshot.id(), denials);
    }
}

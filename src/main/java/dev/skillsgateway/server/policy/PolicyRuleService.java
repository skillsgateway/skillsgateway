package dev.skillsgateway.server.policy;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import io.github.reqstool.annotations.Requirements;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one validated, audited lifecycle path for policy rules (GW_0089), shared by the REST API and
 * the estate reconciler. Every expression is compiled — parsed and type-checked to a boolean —
 * before a row is written; a rule that does not compile is refused, never stored.
 */
@Service
public class PolicyRuleService {

    public static final String EVENT_CREATED = "policy-rule-created";
    public static final String EVENT_UPDATED = "policy-rule-updated";
    public static final String EVENT_DELETED = "policy-rule-deleted";

    /** Same shape as marketplace names; rule names appear in URLs, ledger details and refusals. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,99}$");

    /** Rule lifecycle is not marketplace-scoped; the ledger column is NOT NULL. */
    private static final String NO_MARKETPLACE = "-";

    private final PolicyRuleRepository repository;
    private final AdminAuditLogger auditLogger;

    public PolicyRuleService(PolicyRuleRepository repository, AdminAuditLogger auditLogger) {
        this.repository = repository;
        this.auditLogger = auditLogger;
    }

    @Requirements({"GW_0089"})
    public PolicyRule create(String name, String description, String expression, boolean enabled, String actor) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "rule name must match ^[a-z0-9][a-z0-9_-]*$ and be at most 100 characters");
        }
        compileOrRefuse(expression);
        if (repository.findByName(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a policy rule named '%s' exists".formatted(name));
        }
        PolicyRule rule = repository.create(name, description, expression, enabled, actor);
        auditLogger.record(actor, NO_MARKETPLACE, EVENT_CREATED, null, detail(rule));
        return rule;
    }

    @Requirements({"GW_0089"})
    public PolicyRule update(String name, String description, String expression, boolean enabled, String actor) {
        PolicyRule existing = repository
                .findByName(name)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "no policy rule named '%s'".formatted(name)));
        compileOrRefuse(expression);
        PolicyRule rule = repository.update(existing.id(), description, expression, enabled, actor);
        auditLogger.record(actor, NO_MARKETPLACE, EVENT_UPDATED, null, detail(rule));
        return rule;
    }

    @Requirements({"GW_0089"})
    public void delete(String name, String actor) {
        PolicyRule existing = repository
                .findByName(name)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "no policy rule named '%s'".formatted(name)));
        repository.delete(existing.id());
        auditLogger.record(actor, NO_MARKETPLACE, EVENT_DELETED, null, "rule=%s".formatted(name));
    }

    public List<PolicyRule> list() {
        return repository.list();
    }

    public Optional<PolicyRule> find(String name) {
        return repository.findByName(name);
    }

    /** Write-time compilation (GW_0089): the operator gets the error, not the reviewer. */
    private static void compileOrRefuse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "expression must not be blank");
        }
        try {
            CelPolicy.compile(expression);
        } catch (PolicyExpressionException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
    }

    private static String detail(PolicyRule rule) {
        return "rule=%s enabled=%s".formatted(rule.name(), rule.enabled());
    }
}

package dev.skillsgateway.server.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Policy rules and their playground (GW_0089, GW_0092). */
@RestController
@RequestMapping("/api/policy")
public class PolicyController {

    private final PolicyRuleService ruleService;
    private final RoleService roleService;
    private final SnapshotRepository snapshotRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final SnapshotFactsService factsService;

    public PolicyController(
            PolicyRuleService ruleService,
            RoleService roleService,
            SnapshotRepository snapshotRepository,
            MarketplaceRepository marketplaceRepository,
            SnapshotFactsService factsService) {
        this.ruleService = ruleService;
        this.roleService = roleService;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.factsService = factsService;
    }

    @Schema(description = "Policy rule creation request")
    public record CreateRuleRequest(
            @Schema(
                    description = "Rule name; the identity a denial carries",
                    example = "no-shell-tools",
                    pattern = "^[a-z0-9][a-z0-9_-]*$")
            String name,

            @Schema(description = "What the rule prohibits, for reviewers reading a refusal")
            String description,

            @Schema(
                    description = "CEL expression over the policy variables; must compile to a boolean",
                    example = "skills.exists(s, s.tools.exists(t, t.startsWith(\"Bash\")))")
            String expression,

            @Schema(description = "Whether the rule gates approvals; omitted means enabled")
            Boolean enabled) {}

    @Schema(description = "Policy rule update request; the name is the path")
    public record UpdateRuleRequest(
            @Schema(description = "What the rule prohibits") String description,

            @Schema(description = "CEL expression; must compile to a boolean")
            String expression,

            @Schema(description = "Whether the rule gates approvals; omitted means enabled")
            Boolean enabled) {}

    @Schema(description = "Playground evaluation request")
    public record PlaygroundRequest(
            @Schema(description = "The real snapshot to evaluate against")
            Long snapshotId,

            @Schema(
                    description = "CEL expression to test; it is compiled and evaluated but never stored",
                    example = "skills.exists(s, s.tools.exists(t, t.startsWith(\"Bash\")))")
            String expression) {}

    @Schema(description = "Playground answer: what the expression said, or why it could not say anything")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlaygroundResult(
            @Schema(description = "Whether the expression matched; absent when it errored")
            Boolean matched,

            @Schema(description = "The compile or evaluation error; absent when the expression answered")
            String error) {}

    @PostMapping("/rules")
    @Tag(name = "Policy")
    @Operation(
            summary = "Create a policy deny rule",
            description = "Stores a named CEL deny rule after compiling it — parsing and type-checking to a boolean"
                    + " over the documented variables (snapshot, files, plugins, skills). An expression that does"
                    + " not compile is refused and never stored. Enabled rules are evaluated fail-closed at every"
                    + " approval; a matching or erroring rule refuses it. The creation lands on the audit ledger.")
    @ApiResponse(responseCode = "200", description = "Rule created and, if enabled, in force immediately")
    @ApiResponse(responseCode = "409", description = "A rule of that name exists")
    @ApiResponse(responseCode = "422", description = "Malformed name, or an expression that does not compile")
    @Requirements({"GW_0089"})
    public PolicyRule create(@RequestBody CreateRuleRequest request, Authentication authentication) {
        roleService.requireAdmin(authentication);
        boolean enabled = request.enabled() == null || request.enabled();
        return ruleService.create(
                request.name(), request.description(), request.expression(), enabled, authentication.getName());
    }

    @GetMapping("/rules")
    @Tag(name = "Policy")
    @Operation(
            summary = "List policy rules",
            description = "Every stored rule, enabled or not, with its expression and attribution.")
    @ApiResponse(responseCode = "200", description = "All policy rules")
    @Requirements({"GW_0089"})
    public List<PolicyRule> list(Authentication authentication) {
        roleService.requireAuditor(authentication);
        return ruleService.list();
    }

    @PutMapping("/rules/{name}")
    @Tag(name = "Policy")
    @Operation(
            summary = "Update a policy rule",
            description = "Replaces a rule's description, expression and enabled flag. The new expression is"
                    + " compiled first; an expression that does not compile is refused and the stored rule is"
                    + " unchanged. Disabling a rule is the audited off-switch — there is no per-snapshot waiver"
                    + " of a policy denial.")
    @ApiResponse(responseCode = "200", description = "Rule updated")
    @ApiResponse(responseCode = "404", description = "No rule of that name")
    @ApiResponse(responseCode = "422", description = "An expression that does not compile")
    @Requirements({"GW_0089"})
    public PolicyRule update(
            @PathVariable String name, @RequestBody UpdateRuleRequest request, Authentication authentication) {
        roleService.requireAdmin(authentication);
        boolean enabled = request.enabled() == null || request.enabled();
        return ruleService.update(name, request.description(), request.expression(), enabled, authentication.getName());
    }

    @DeleteMapping("/rules/{name}")
    @Tag(name = "Policy")
    @Operation(
            summary = "Delete a policy rule",
            description = "Removes the rule; the deletion lands on the audit ledger. Past denials it decided stay"
                    + " on the append-only ledger.")
    @ApiResponse(responseCode = "200", description = "Rule deleted")
    @ApiResponse(responseCode = "404", description = "No rule of that name")
    @Requirements({"GW_0089"})
    public Map<String, String> delete(@PathVariable String name, Authentication authentication) {
        roleService.requireAdmin(authentication);
        ruleService.delete(name, authentication.getName());
        return Map.of("deleted", name);
    }

    @PostMapping("/playground")
    @Tag(name = "Policy")
    @Operation(
            summary = "Test an expression against a real snapshot",
            description = "Compiles and evaluates any CEL expression over a real snapshot's facts — held, approved"
                    + " or revoked — and answers matched or the error. Read-only by contract: nothing is stored,"
                    + " nothing lands on the ledger, no state changes; the answer never carries snapshot content."
                    + " This is how a rule is tested before it is enforced. Requires permission to approve the"
                    + " named snapshot.")
    @ApiResponse(responseCode = "200", description = "The expression's answer, or its error")
    @ApiResponse(responseCode = "404", description = "No such snapshot")
    @Requirements({"GW_0092"})
    public PlaygroundResult playground(@RequestBody PlaygroundRequest request, Authentication authentication) {
        long snapshotId = request.snapshotId() == null ? -1L : request.snapshotId();
        roleService.requireApproverOfSnapshot(authentication, snapshotId);
        Snapshot snapshot =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        Marketplace marketplace = marketplaceRepository
                .findById(snapshot.marketplaceId())
                .orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        CelPolicy.Compiled compiled;
        try {
            compiled = CelPolicy.compile(request.expression() == null ? "" : request.expression());
        } catch (PolicyExpressionException e) {
            return new PlaygroundResult(null, e.getMessage());
        }
        try {
            return new PlaygroundResult(CelPolicy.matches(compiled, factsService.build(snapshot, marketplace)), null);
        } catch (PolicyEvaluationException e) {
            return new PlaygroundResult(null, e.getMessage());
        }
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ProblemDetail snapshotNotFound(SnapshotNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}

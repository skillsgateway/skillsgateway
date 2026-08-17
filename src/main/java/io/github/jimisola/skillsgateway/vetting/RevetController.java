package io.github.jimisola.skillsgateway.vetting;

import io.github.jimisola.skillsgateway.persistence.FetchLogRepository;
import io.github.jimisola.skillsgateway.persistence.SnapshotNotFoundException;
import io.github.jimisola.skillsgateway.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Re-vetting on demand, and the blast radius of a violation (GW_0049, GW_0053).
 *
 * <p>The manual endpoints exist for the case the scheduled sweep cannot serve: a scanner or
 * advisory feed has moved and the answer is wanted now, not at the next tick. The built-in
 * connectors have no external feed to subscribe to, so an operator calling these after updating a
 * connector <em>is</em> the feed integration in v1; a webhook-triggered one is a follow-on.
 */
@RestController
@RequestMapping("/api")
public class RevetController {

    private final RevetService revetService;
    private final RoleService roleService;

    public RevetController(RevetService revetService, RoleService roleService) {
        this.revetService = revetService;
        this.roleService = roleService;
    }

    @PostMapping("/snapshots/{id}/revet")
    @Requirements({"GW_0049"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "Re-vet an approved snapshot now",
            description = "Runs the vetting chain again over the snapshot's pinned content and records a new run"
                    + " with trigger revet-manual. If the run's effective outcome — after the waivers active"
                    + " right now — objects to the content, the violation is written to the ledger and announced"
                    + " as snapshot.revet_violation. In enforce mode the snapshot is then revoked and its"
                    + " published refs are removed; in warn mode, the default, publication is untouched. A run"
                    + " that only blocks because a connector errored or has not answered is recorded as"
                    + " inconclusive and never revokes anything.")
    @ApiResponse(responseCode = "200", description = "The re-vetting run and what it concluded")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    @ApiResponse(responseCode = "409", description = "The snapshot is not approved; only served content is re-vetted")
    public RevetService.RevetResult revetSnapshot(@PathVariable long id, Authentication authentication) {
        roleService.requireApproverOfSnapshot(authentication, id);
        return revetService.revetSnapshot(id, authentication.getName());
    }

    @PostMapping("/marketplaces/{name}/revet")
    @Requirements({"GW_0049"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "Re-vet every approved snapshot of a marketplace now",
            description = "Runs the vetting chain again over every live approved snapshot of the marketplace, one"
                    + " run each, and applies the configured re-vetting mode to each result. This is the"
                    + " operational answer to a connector rule set or advisory feed that has just been updated.")
    @ApiResponse(responseCode = "200", description = "What the pass re-vetted and concluded")
    @ApiResponse(responseCode = "404", description = "Marketplace not found")
    public RevetService.PassResult revetMarketplace(@PathVariable String name, Authentication authentication) {
        roleService.requireApprover(authentication, name);
        return revetService.revetMarketplace(name, authentication.getName());
    }

    @GetMapping("/snapshots/{id}/fetchers")
    @Requirements({"GW_0053"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "Who fetched this snapshot",
            description = "Every authenticated identity that received this snapshot's content through the git"
                    + " facade, with how many times and when it last did — the blast radius of a retroactive"
                    + " violation, answered from the append-only fetch ledger. Only pack transfers count: a ref"
                    + " advertisement means the client asked, not that it received anything.")
    @ApiResponse(responseCode = "200", description = "The identities that fetched the snapshot's content")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    public List<FetchLogRepository.Fetcher> fetchers(@PathVariable long id) {
        return revetService.affected(id);
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ProblemDetail snapshotNotFound(SnapshotNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail notApproved(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail marketplaceNotFound(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}

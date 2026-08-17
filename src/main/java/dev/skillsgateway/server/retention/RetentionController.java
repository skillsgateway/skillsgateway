package dev.skillsgateway.server.retention;

import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The retention surface: an administrator's own delete and restore, and the on-demand passes an
 * operator uses to inspect and apply a policy without waiting for (or enabling) the scheduler.
 */
@RestController
@RequestMapping("/api")
public class RetentionController {

    private final RetentionService retentionService;
    private final RoleService roleService;

    public RetentionController(RetentionService retentionService, RoleService roleService) {
        this.retentionService = retentionService;
        this.roleService = roleService;
    }

    @DeleteMapping("/snapshots/{id}")
    @Requirements({"GW_0032", "GW_0033"})
    @Tag(name = "Retention")
    @Operation(
            summary = "Soft-delete a snapshot",
            description = "Marks the snapshot deleted and restorable until the end of the marketplace's restore"
                    + " window; its vetting state is unchanged. Approved snapshots are served by the git facade"
                    + " and can never be deleted.")
    @ApiResponse(responseCode = "200", description = "Snapshot marked deleted")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    @ApiResponse(responseCode = "409", description = "Snapshot is approved, or already deleted")
    public Snapshot softDelete(@PathVariable long id, Authentication authentication) {
        roleService.requireAdmin(authentication);
        return retentionService.softDelete(id, RetentionService.MANUAL_REASON, authentication.getName());
    }

    @PostMapping("/snapshots/{id}/restore")
    @Requirements({"GW_0032"})
    @Tag(name = "Retention")
    @Operation(
            summary = "Restore a soft-deleted snapshot",
            description = "Clears the deletion marks, provided compaction has not yet removed the snapshot.")
    @ApiResponse(responseCode = "200", description = "Snapshot restored")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    @ApiResponse(responseCode = "409", description = "Snapshot is not deleted")
    public Snapshot restore(@PathVariable long id, Authentication authentication) {
        roleService.requireAdmin(authentication);
        return retentionService.restore(id, authentication.getName());
    }

    @GetMapping("/retention/candidates")
    @Requirements({"GW_0031"})
    @Tag(name = "Retention")
    @Operation(
            summary = "Preview the retention candidates",
            description = "The snapshots the policies in force would delete right now, each with the criterion"
                    + " that selected it. A dry run: nothing is written.")
    @ApiResponse(responseCode = "200", description = "Candidate snapshots")
    public List<RetentionService.Candidate> candidates(
            @RequestParam(required = false) @Parameter(description = "Restrict to one marketplace") String marketplace,
            Authentication authentication) {
        roleService.requireAuditor(authentication);
        return retentionService.candidates(marketplace);
    }

    @PostMapping("/retention/evaluate")
    @Requirements({"GW_0031"})
    @Tag(name = "Retention")
    @Operation(
            summary = "Run a retention evaluation pass",
            description = "Soft-deletes every snapshot the policies select. Equivalent to what the scheduled pass"
                    + " does when retention is enabled.")
    @ApiResponse(responseCode = "200", description = "Pass outcome")
    public RetentionService.PassResult evaluate(
            @RequestParam(required = false) @Parameter(description = "Restrict to one marketplace") String marketplace,
            Authentication authentication) {
        roleService.requireAdmin(authentication);
        return retentionService.evaluate(authentication.getName(), marketplace);
    }

    @PostMapping("/retention/compact")
    @Requirements({"GW_0034"})
    @Tag(name = "Retention")
    @Operation(
            summary = "Run a compaction pass",
            description = "Permanently removes every soft-deleted snapshot whose restore window has elapsed,"
                    + " together with its pinned commit in the quarantine repository.")
    @ApiResponse(responseCode = "200", description = "Pass outcome")
    public RetentionService.PassResult compact(Authentication authentication) {
        roleService.requireAdmin(authentication);
        return retentionService.compact(authentication.getName());
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ProblemDetail snapshotNotFound(SnapshotNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}

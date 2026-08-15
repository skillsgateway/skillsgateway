package io.github.jimisola.skillsgateway.vetting;

import io.github.jimisola.skillsgateway.persistence.SnapshotNotFoundException;
import io.github.jimisola.skillsgateway.persistence.SnapshotRepository;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The reviewer's evidence: what the vetting chain concluded about a snapshot, and why. */
@RestController
@RequestMapping("/api")
public class VettingController {

    private final VettingService vettingService;
    private final SnapshotRepository snapshotRepository;

    public VettingController(VettingService vettingService, SnapshotRepository snapshotRepository) {
        this.vettingService = vettingService;
        this.snapshotRepository = snapshotRepository;
    }

    @Schema(description = "A connector configured in the vetting chain")
    public record ConnectorView(
            @Schema(description = "Stable connector name") String name,
            @Schema(description = "Position in the chain") int order,

            @Schema(description = "What the connector looks for, and what it cannot see")
            String description) {}

    @Schema(description = "A snapshot's latest vetting chain run, and the chain that produced it")
    public record VettingView(
            @Schema(description = "Snapshot id") long snapshotId,

            @Schema(
                    description = "Fail-closed aggregate of the run; a snapshot with no run is blocked",
                    allowableValues = {"CLEAR", "BLOCKED"})
            VettingChain.Outcome outcome,

            @Schema(description = "The latest run with its verdicts and findings, or null if the chain never ran")
            VettingRepository.Run run,

            @Schema(description = "The connectors configured in the chain, in the order they run")
            List<ConnectorView> connectors) {}

    @GetMapping("/snapshots/{id}/vetting")
    @Requirements({"GW_0037", "GW_0038"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "Snapshot vetting verdicts",
            description = "The snapshot's latest vetting chain run: each connector's verdict in chain order,"
                    + " the findings behind it, and the fail-closed aggregate that gates approval. A snapshot"
                    + " the chain has never run against reports a blocked outcome and no run.")
    @ApiResponse(responseCode = "200", description = "The latest chain run and the configured chain")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    public VettingView vetting(@PathVariable long id) {
        snapshotRepository.findById(id).orElseThrow(() -> new SnapshotNotFoundException(id));
        List<ConnectorView> connectors = vettingService.connectors().stream()
                .map(connector -> new ConnectorView(connector.name(), connector.order(), connector.description()))
                .toList();
        return vettingService
                .latestRun(id)
                .map(run -> new VettingView(id, run.outcome(), run, connectors))
                .orElseGet(() -> new VettingView(id, VettingChain.Outcome.BLOCKED, null, connectors));
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ProblemDetail snapshotNotFound(SnapshotNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}

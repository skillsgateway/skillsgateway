package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.persistence.SnapshotRepository;
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

/**
 * The reviewer's evidence: what the vetting chain concluded about a snapshot, why, and which of
 * those findings an accepted risk is currently suppressing.
 */
@RestController
@RequestMapping("/api")
public class VettingController {

    private final VettingService vettingService;
    private final WaiverService waiverService;
    private final SnapshotRepository snapshotRepository;

    public VettingController(
            VettingService vettingService, WaiverService waiverService, SnapshotRepository snapshotRepository) {
        this.vettingService = vettingService;
        this.waiverService = waiverService;
        this.snapshotRepository = snapshotRepository;
    }

    @Schema(description = "A connector configured in the vetting chain")
    public record ConnectorView(
            @Schema(description = "Stable connector name") String name,
            @Schema(description = "Position in the chain") int order,

            @Schema(description = "What the connector looks for, and what it cannot see")
            String description) {}

    @Schema(description = "A snapshot's latest vetting chain run, the waivers over it, and the chain that produced it")
    public record VettingView(
            @Schema(description = "Snapshot id") long snapshotId,

            @Schema(
                    description = "The effective outcome, which is what gates approval: the run's verdicts with"
                            + " every waived finding removed. CLEAR_WITH_WAIVERS means nothing objects any more"
                            + " only because an active waiver is suppressing a finding. A snapshot with no run"
                            + " is blocked.",
                    allowableValues = {"CLEAR", "CLEAR_WITH_WAIVERS", "BLOCKED"})
            VettingChain.Outcome outcome,

            @Schema(
                    description = "What the connectors themselves concluded, before any waiver was applied",
                    allowableValues = {"CLEAR", "BLOCKED"})
            VettingChain.Outcome recordedOutcome,

            @Schema(description = "The latest run with its verdicts and findings, or null if the chain never ran")
            VettingRepository.Run run,

            @Schema(
                    description =
                            "Findings an active waiver is currently suppressing, keyed by connector, rule and location")
            List<WaiverEvaluation.Suppression> suppressed,

            @Schema(description = "Blocking findings that no active waiver covers; the waivers approval still needs")
            List<WaiverEvaluation.UncoveredFinding> uncovered,

            @Schema(
                    description = "Waivers of this snapshot's marketplace whose rule appears in this run, active"
                            + " and lapsed alike, so an expired acceptance stays visible")
            List<WaiverController.WaiverView> waivers,

            @Schema(description = "The connectors configured in the chain, in the order they run")
            List<ConnectorView> connectors) {}

    @GetMapping("/snapshots/{id}/vetting")
    @Requirements({"GW_0037", "GW_0038", "GW_0045"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "Snapshot vetting verdicts",
            description = "The snapshot's latest vetting chain run: each connector's verdict in chain order,"
                    + " the findings behind it, the waivers currently suppressing any of them, and the"
                    + " fail-closed effective aggregate that gates approval. A snapshot the chain has never run"
                    + " against reports a blocked outcome and no run.")
    @ApiResponse(responseCode = "200", description = "The latest chain run and the configured chain")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    public VettingView vetting(@PathVariable long id) {
        Snapshot snapshot = snapshotRepository.findById(id).orElseThrow(() -> new SnapshotNotFoundException(id));
        List<ConnectorView> connectors = vettingService.connectors().stream()
                .map(connector -> new ConnectorView(connector.name(), connector.order(), connector.description()))
                .toList();
        VettingRepository.Run run = vettingService.latestRun(id).orElse(null);
        // Evaluated here rather than read from a column: the effective outcome is a function of
        // the run and the waivers active at this instant, so an expired waiver stops suppressing
        // on this very request without anything having had to run in the background (GW_0046).
        WaiverEvaluation.Effect effect = waiverService.evaluate(snapshot);
        List<Waiver> waivers = waiverService.forSnapshot(snapshot);
        List<String> rulesInRun = run == null
                ? List.of()
                : run.verdicts().stream()
                        .flatMap(verdict -> verdict.findings().stream())
                        .map(Finding::id)
                        .distinct()
                        .toList();
        List<WaiverController.WaiverView> relevant = waivers.stream()
                .filter(waiver -> rulesInRun.contains(waiver.ruleId()))
                .map(WaiverController.WaiverView::of)
                .toList();
        return new VettingView(
                id,
                effect.outcome(),
                effect.recordedOutcome(),
                run,
                effect.suppressions(),
                effect.uncovered(),
                relevant,
                connectors);
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ProblemDetail snapshotNotFound(SnapshotNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}

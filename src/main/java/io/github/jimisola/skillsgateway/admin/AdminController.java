package io.github.jimisola.skillsgateway.admin;

import io.github.jimisola.skillsgateway.approval.ApprovalService;
import io.github.jimisola.skillsgateway.ingestion.IngestionException;
import io.github.jimisola.skillsgateway.ingestion.IngestionService;
import io.github.jimisola.skillsgateway.persistence.FetchLogRepository;
import io.github.jimisola.skillsgateway.persistence.Marketplace;
import io.github.jimisola.skillsgateway.persistence.MarketplaceRepository;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.jimisola.skillsgateway.persistence.SnapshotNotFoundException;
import io.github.jimisola.skillsgateway.persistence.SnapshotRepository;
import io.github.reqstool.annotations.Requirements;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class AdminController {

    private static final Pattern MARKETPLACE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");

    private final MarketplaceRepository marketplaceRepository;
    private final SnapshotRepository snapshotRepository;
    private final IngestionService ingestionService;
    private final ApprovalService approvalService;
    private final FetchLogRepository fetchLogRepository;

    public AdminController(
            MarketplaceRepository marketplaceRepository,
            SnapshotRepository snapshotRepository,
            IngestionService ingestionService,
            ApprovalService approvalService,
            FetchLogRepository fetchLogRepository) {
        this.marketplaceRepository = marketplaceRepository;
        this.snapshotRepository = snapshotRepository;
        this.ingestionService = ingestionService;
        this.approvalService = approvalService;
        this.fetchLogRepository = fetchLogRepository;
    }

    public record RegisterMarketplaceRequest(String name, String url) {}

    public record MarketplaceView(long id, String name, String url, Instant createdAt, List<Snapshot> snapshots) {}

    @PostMapping("/marketplaces")
    public ResponseEntity<Marketplace> registerMarketplace(@RequestBody RegisterMarketplaceRequest request) {
        if (request.name() == null || !MARKETPLACE_NAME.matcher(request.name()).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "name must match " + MARKETPLACE_NAME.pattern());
        }
        if (marketplaceRepository.findByName(request.name()).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "marketplace '%s' already exists".formatted(request.name()));
        }
        Marketplace marketplace = marketplaceRepository.register(request.name(), request.url());
        return ResponseEntity.status(HttpStatus.CREATED).body(marketplace);
    }

    @GetMapping("/marketplaces")
    @Requirements({"GW_0010"})
    public List<MarketplaceView> listMarketplaces() {
        return marketplaceRepository.list().stream()
                .map(marketplace -> new MarketplaceView(
                        marketplace.id(),
                        marketplace.name(),
                        marketplace.url(),
                        marketplace.createdAt(),
                        snapshotRepository.listByMarketplace(marketplace.id())))
                .toList();
    }

    @PostMapping("/marketplaces/{name}/ingest")
    public ResponseEntity<Snapshot> ingest(@PathVariable String name) {
        Marketplace marketplace = marketplaceRepository
                .findByName(name)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(name)));
        Snapshot snapshot = ingestionService.ingest(marketplace);
        return ResponseEntity.status(HttpStatus.CREATED).body(snapshot);
    }

    @PostMapping("/snapshots/{id}/approve")
    public Snapshot approve(@PathVariable long id, Authentication authentication) {
        return approvalService.approve(id, authentication.getName());
    }

    @PostMapping("/snapshots/{id}/reject")
    public Snapshot reject(@PathVariable long id, Authentication authentication) {
        return approvalService.reject(id, authentication.getName());
    }

    @GetMapping("/snapshots/{id}/provenance")
    public ApprovalService.Provenance provenance(@PathVariable long id) {
        return approvalService
                .provenance(id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "snapshot %d not found".formatted(id)));
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit() {
        return fetchLogRepository.list();
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ProblemDetail snapshotNotFound(SnapshotNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail invalidTransition(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IngestionException.class)
    public ProblemDetail ingestionFailed(IngestionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}

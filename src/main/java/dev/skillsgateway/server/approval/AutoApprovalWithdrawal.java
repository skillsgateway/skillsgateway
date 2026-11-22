package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.approval.RevocationService.ServeAfter;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.ServedTip;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every automatic approval since a time, withdrawn in one act (GW_APPROVAL_0044.14): the answer to
 * an internal publisher found compromised, whose pushes the gateway published with no person.
 *
 * <p>Each withdrawal is an ordinary {@link RevocationService#revoke}, newest decision first, so each
 * is ledgered and announced as one today. Only a marketplace's served tip takes the caller's
 * {@link ServeAfter}; a return skips every snapshot this act withdraws, and a withdrawn snapshot
 * that is not served leaves the served reference alone.
 */
@Service
public class AutoApprovalWithdrawal {

    private final SnapshotRepository snapshotRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final ServedTip servedTip;
    private final RevocationService revocationService;
    private final AutoApprovalSettingService settingService;

    public AutoApprovalWithdrawal(
            SnapshotRepository snapshotRepository,
            MarketplaceRepository marketplaceRepository,
            ServedTip servedTip,
            RevocationService revocationService,
            AutoApprovalSettingService settingService) {
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.servedTip = servedTip;
        this.revocationService = revocationService;
        this.settingService = settingService;
    }

    @Schema(description = "Withdraw every automatic approval since a time")
    public record WithdrawRequest(
            @Schema(description = "Withdraw the live snapshots approved automatically at or after this time")
            Instant since,

            @Schema(description = "Why; recorded on each withdrawal")
            String reason,

            @Schema(
                    description = "What a marketplace whose served snapshot is withdrawn serves afterwards:"
                            + " its newest approved snapshot this request does not withdraw, or nothing",
                    allowableValues = {"previous_approved", "nothing"})
            ServeAfter serveAfter) {}

    @Schema(description = "One snapshot the request withdrew")
    public record Withdrawn(
            @Schema(description = "The withdrawn snapshot") Snapshot snapshot,

            @Schema(description = "What its marketplace serves now, or null when it serves nothing")
            Snapshot nowServing) {}

    @Schema(description = "What a bulk withdrawal withdrew, newest decision first")
    public record WithdrawResponse(
            List<Withdrawn> withdrawn,

            @Schema(
                    description = "The marketplaces whose automatic approval this request turned off, because"
                            + " it withdrew their automatic approvals and the next push would otherwise be"
                            + " approved the same way")
            List<String> turnedOff) {}

    /**
     * @throws RollbackUnavailableException before anything is withdrawn, when a return was asked for
     *     and a marketplace whose served snapshot is withdrawn would have nothing to return to
     */
    @Requirements({"GW_APPROVAL_0044.14"})
    public WithdrawResponse withdraw(WithdrawRequest request, String administrator) {
        if (request == null || request.since() == null) {
            throw unprocessable("since is required: the time from which automatic approvals are withdrawn");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw unprocessable("a reason is required; it is recorded on every withdrawal");
        }
        if (request.serveAfter() == null) {
            throw unprocessable("serveAfter is required: previous_approved or nothing");
        }
        List<Snapshot> doomed = snapshotRepository.approvedBySince(AutoApprovalService.ACTOR, request.since());
        Set<Long> ids = doomed.stream().map(Snapshot::id).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        for (Snapshot snapshot : doomed) {
            names.computeIfAbsent(
                    snapshot.marketplaceId(),
                    id -> marketplaceRepository.findById(id).orElseThrow().name());
        }
        if (request.serveAfter() == ServeAfter.PREVIOUS_APPROVED) {
            for (Snapshot snapshot : doomed) {
                if (served(snapshot, names)
                        && snapshotRepository
                                .previousApproved(snapshot.marketplaceId(), ids)
                                .isEmpty()) {
                    throw new RollbackUnavailableException(snapshot.id(), names.get(snapshot.marketplaceId()));
                }
            }
        }
        List<Withdrawn> withdrawn = new ArrayList<>();
        for (Snapshot snapshot : doomed) {
            ServeAfter choice = served(snapshot, names) ? request.serveAfter() : ServeAfter.NOTHING;
            RevocationService.Revoked revoked;
            try {
                revoked = revocationService.revoke(snapshot.id(), request.reason(), choice, administrator, ids);
            } catch (NotApprovedException withdrawnMeanwhile) {
                continue;
            }
            Snapshot nowServing = servedTip
                    .of(names.get(snapshot.marketplaceId()))
                    .flatMap(sha -> snapshotRepository.findByMarketplaceAndSha(snapshot.marketplaceId(), sha))
                    .orElse(null);
            withdrawn.add(new Withdrawn(revoked.snapshot(), nowServing));
        }
        List<String> turnedOff = new ArrayList<>();
        for (String name : withdrawn.stream()
                .map(w -> names.get(w.snapshot().marketplaceId()))
                .distinct()
                .toList()) {
            if (settingService.turnOffAfterWithdrawal(name, administrator, "withdrawn: " + request.reason())) {
                turnedOff.add(name);
            }
        }
        return new WithdrawResponse(withdrawn, turnedOff);
    }

    private boolean served(Snapshot snapshot, Map<Long, String> names) {
        return servedTip
                .of(names.get(snapshot.marketplaceId()))
                .filter(snapshot.sha()::equals)
                .isPresent();
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}

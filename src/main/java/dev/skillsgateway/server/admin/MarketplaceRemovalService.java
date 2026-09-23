package dev.skillsgateway.server.admin;

import dev.skillsgateway.server.approval.NotApprovedException;
import dev.skillsgateway.server.approval.RevocationService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.persistence.TokenRepository;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Removing a marketplace (GW_INGEST_0034): a retirement that behaves like removal. The row, its
 * snapshots and the ledger stay; the marketplace stops being served, synced, pushed to, listed and
 * decided on.
 *
 * <p>Withdrawal goes through {@link RevocationService}, one approved snapshot at a time, rather than
 * through storage directly, so a removal is recorded, announced and answered to holders exactly as a
 * person withdrawing each snapshot would be. The row is stamped first: from that commit on an
 * approval of this marketplace is refused, so none can land behind the withdrawals.
 */
@Service
public class MarketplaceRemovalService {

    /** Ledger event for the removal itself; each withdrawal it makes has its own entries. */
    public static final String EVENT_REMOVED = "marketplace-removed";

    /** Ledger event for the publication grants a removal takes away. */
    public static final String EVENT_PUSH_SCOPES_REMOVED = "marketplace-push-scopes-removed";

    /** Prefixed to the administrator's reason on every withdrawal a removal makes. */
    public static final String WITHDRAWAL_REASON_PREFIX = "marketplace removed: ";

    private final MarketplaceRepository marketplaceRepository;
    private final SnapshotRepository snapshotRepository;
    private final RevocationService revocationService;
    private final AdminAuditLogger auditLogger;
    private final WebhookService webhookService;
    private final TokenRepository tokenRepository;

    public MarketplaceRemovalService(
            MarketplaceRepository marketplaceRepository,
            SnapshotRepository snapshotRepository,
            RevocationService revocationService,
            AdminAuditLogger auditLogger,
            WebhookService webhookService,
            TokenRepository tokenRepository) {
        this.marketplaceRepository = marketplaceRepository;
        this.snapshotRepository = snapshotRepository;
        this.revocationService = revocationService;
        this.auditLogger = auditLogger;
        this.webhookService = webhookService;
        this.tokenRepository = tokenRepository;
    }

    @Schema(description = "A removed marketplace and what its removal withdrew")
    public record Removal(
            @Schema(description = "Id of the removed marketplace")
            long id,

            @Schema(description = "Its name, which may now be registered again")
            String name,

            @Schema(description = "When the removal was recorded")
            Instant removedAt,

            @Schema(description = "Identity that removed it")
            String removedBy,

            @Schema(description = "Approved snapshots the removal withdrew")
            List<Long> withdrawnSnapshotIds,

            @Schema(
                    description = "Tokens that lost their grant to publish to this name. Fetch grants are kept,"
                            + " so clients keep working if the name is registered again")
            List<Long> pushScopeRemovedFromTokenIds) {}

    /**
     * @throws MissingRemovalReasonException if the reason is absent or blank — before anything is read
     * @throws ResponseStatusException 404 if no live marketplace has this name, including when a
     *     concurrent removal won
     */
    @Requirements({"GW_INGEST_0034", "GW_WEBHOOK_0010"})
    public Removal remove(String name, String reason, String administrator) {
        if (reason == null || reason.isBlank()) {
            throw new MissingRemovalReasonException(name);
        }
        String stated = reason.strip();
        Marketplace marketplace = marketplaceRepository.findByName(name).orElseThrow(() -> notFound(name));
        Instant removedAt = marketplaceRepository
                .retire(marketplace.id(), administrator, stated)
                .orElseThrow(() -> notFound(name));

        // Before the withdrawals: a publication grant is a name, and nothing about this marketplace
        // should still be able to publish under it — least of all into a successor.
        List<Long> unscoped = tokenRepository.removePushScope(marketplace.name());
        if (!unscoped.isEmpty()) {
            auditLogger.record(administrator, marketplace, EVENT_PUSH_SCOPES_REMOVED, null, "tokens=" + unscoped);
        }

        List<Long> withdrawn = new ArrayList<>();
        for (Snapshot snapshot : snapshotRepository.approvedByMarketplace(marketplace.id())) {
            try {
                revocationService.revoke(
                        snapshot.id(),
                        WITHDRAWAL_REASON_PREFIX + stated,
                        RevocationService.ServeAfter.NOTHING,
                        administrator);
                withdrawn.add(snapshot.id());
            } catch (NotApprovedException alreadyWithdrawn) {
                // A concurrent withdrawal got there first; the snapshot is withdrawn either way.
            }
        }

        String summary = "withdrew %d approved snapshot(s)".formatted(withdrawn.size());
        auditLogger.record(administrator, marketplace, EVENT_REMOVED, null, summary + "; reason: " + stated);
        // No reason on the event: a webhook target is authorised by a URL allowlist, not an identity.
        webhookService.emitMarketplace(WebhookEvent.MARKETPLACE_REMOVED, marketplace.name(), administrator, summary);
        return new Removal(
                marketplace.id(),
                marketplace.name(),
                removedAt,
                administrator,
                List.copyOf(withdrawn),
                List.copyOf(unscoped));
    }

    private static ResponseStatusException notFound(String name) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(name));
    }
}

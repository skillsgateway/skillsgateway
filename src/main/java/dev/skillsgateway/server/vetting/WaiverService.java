package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import io.github.reqstool.annotations.Requirements;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Waivers: creation with the fields that make an acceptance reviewable, revocation, and the
 * effective-outcome evaluation the approval gate and the reviewer surface both read (GW_0044,
 * GW_0045, GW_0046, GW_0048).
 *
 * <p>The service owns three things the pure {@link WaiverEvaluation} cannot: the clock, the
 * database, and the ledger. Everything that decides whether a waiver applies stays in the pure
 * function, so the decision is testable without any of those.
 */
@Service
public class WaiverService {

    /** Ledger events, so the auditor's grep terms are one list rather than scattered literals. */
    public static final String EVENT_CREATED = "waiver-created";

    public static final String EVENT_REVOKED = "waiver-revoked";
    public static final String EVENT_EXPIRED = "waiver-expired";
    public static final String EVENT_APPLIED = "waiver-applied";

    private final WaiverRepository waiverRepository;
    private final VettingRepository vettingRepository;
    private final SnapshotRepository snapshotRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final AdminAuditLogger auditLogger;

    public WaiverService(
            WaiverRepository waiverRepository,
            VettingRepository vettingRepository,
            SnapshotRepository snapshotRepository,
            MarketplaceRepository marketplaceRepository,
            AdminAuditLogger auditLogger) {
        this.waiverRepository = waiverRepository;
        this.vettingRepository = vettingRepository;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.auditLogger = auditLogger;
    }

    /**
     * Records a waiver against a finding seen on a snapshot (GW_0044, GW_0048).
     *
     * <p>Anchoring creation to the snapshot rather than to a marketplace name is what makes
     * mis-scoping unrepresentable: the marketplace and — for a snapshot-scoped waiver — the commit
     * SHA are read from the snapshot row, so no request can pair a marketplace with a SHA that
     * does not belong to it.
     *
     * @param scopeValue the path for a path-scoped waiver; ignored for a snapshot-scoped one,
     *     which always takes the snapshot's own SHA
     */
    @Requirements({"GW_0044", "GW_0048"})
    public Waiver create(
            long snapshotId,
            String ruleId,
            WaiverScope scope,
            String scopeValue,
            String justification,
            Instant expiresAt,
            String approver) {
        Snapshot snapshot =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        if (ruleId == null || ruleId.isBlank()) {
            throw new WaiverValidationException("a waiver must name the finding rule it accepts");
        }
        if (scope == null) {
            throw new WaiverValidationException("a waiver must name its scope: snapshot or path");
        }
        if (justification == null || justification.isBlank()) {
            throw new WaiverValidationException("a waiver must carry a justification");
        }
        if (approver == null || approver.isBlank()) {
            throw new WaiverValidationException("a waiver must carry the identity accepting the risk");
        }
        // The whole point of the feature: there is no way to express "forever".
        if (expiresAt == null) {
            throw new WaiverValidationException("a waiver must carry an expiry; unlimited waivers are not accepted");
        }
        if (!expiresAt.isAfter(Instant.now())) {
            throw new WaiverValidationException("a waiver's expiry must be in the future");
        }
        String value = scope == WaiverScope.SNAPSHOT ? snapshot.sha() : normalizePath(scopeValue);
        Marketplace marketplace = marketplaceRepository
                .findById(snapshot.marketplaceId())
                .orElseThrow(() ->
                        new WaiverValidationException("marketplace %d not found".formatted(snapshot.marketplaceId())));

        Waiver waiver = waiverRepository.create(
                snapshot.marketplaceId(), ruleId.trim(), scope, value, justification.trim(), approver, expiresAt);
        auditLogger.record(
                approver,
                marketplace.name(),
                EVENT_CREATED,
                snapshot.sha(),
                "rule=%s; scope=%s:%s; expires=%s".formatted(ruleId.trim(), scope.stored(), value, expiresAt));
        return waiver;
    }

    /** Withdraws a waiver and says so in the ledger (GW_0046, GW_0048). */
    @Requirements({"GW_0046", "GW_0048"})
    public Optional<Waiver> revoke(long waiverId, String principal) {
        Optional<Waiver> existing = waiverRepository.findById(waiverId);
        if (existing.isEmpty() || !waiverRepository.revoke(waiverId, principal)) {
            return Optional.empty();
        }
        Waiver waiver = existing.orElseThrow();
        auditLogger.record(
                principal,
                waiver.marketplace(),
                EVENT_REVOKED,
                waiver.scope() == WaiverScope.SNAPSHOT ? waiver.scopeValue() : null,
                "rule=%s; scope=%s:%s".formatted(waiver.ruleId(), waiver.scope().stored(), waiver.scopeValue()));
        // Re-read so the caller sees the revocation stamp rather than the pre-revocation row.
        return waiverRepository.findById(waiverId);
    }

    public List<Waiver> byMarketplace(long marketplaceId) {
        return waiverRepository.byMarketplace(marketplaceId);
    }

    /** Every waiver of the marketplace a snapshot belongs to, whether active or not. */
    public List<Waiver> forSnapshot(Snapshot snapshot) {
        return waiverRepository.byMarketplace(snapshot.marketplaceId());
    }

    /**
     * The snapshot's effective vetting outcome as of now (GW_0045, GW_0046) — the value the
     * approval gate and the reviewer surface both consult. Read at every call, never cached: that
     * is what makes an expired waiver stop suppressing without a scheduler.
     */
    @Requirements({"GW_0045", "GW_0046"})
    public WaiverEvaluation.Effect evaluate(long snapshotId) {
        return snapshotRepository.findById(snapshotId).map(this::evaluate).orElseGet(WaiverEvaluation::noRun);
    }

    /** {@link #evaluate(long)} for a snapshot already in hand. */
    @Requirements({"GW_0045", "GW_0046"})
    public WaiverEvaluation.Effect evaluate(Snapshot snapshot) {
        return WaiverEvaluation.evaluate(
                vettingRepository.latestRun(snapshot.id()).orElse(null),
                waiverRepository.byMarketplace(snapshot.marketplaceId()),
                snapshot.sha(),
                Instant.now());
    }

    /**
     * Appends one {@code waiver-applied} entry per waiver that suppressed a finding for this
     * approval (GW_0048) — the "use" half of the lifecycle, and the entry that lets an auditor
     * answer "why is a snapshot with a critical finding being served" from the ledger alone.
     */
    @Requirements({"GW_0048"})
    public void recordUse(String marketplace, String sha, String principal, List<WaiverEvaluation.Suppression> used) {
        for (WaiverEvaluation.Suppression suppression : used) {
            auditLogger.record(
                    principal,
                    marketplace,
                    EVENT_APPLIED,
                    sha,
                    "waiver=%d; rule=%s; location=%s; approvedBy=%s; expires=%s"
                            .formatted(
                                    suppression.waiverId(),
                                    suppression.ruleId(),
                                    suppression.location(),
                                    suppression.approvedBy(),
                                    suppression.expiresAt()));
        }
    }

    /**
     * Notes newly-lapsed waivers in the ledger, once each (GW_0048). Carries no gate authority:
     * expiry is already in force the moment {@link #evaluate(long)} next runs, so this pass only
     * decides whether the lapse is *visible* in the ledger, never whether it applies.
     *
     * @return how many waivers were recorded as expired
     */
    @Requirements({"GW_0048"})
    public int sweepExpired(int batchSize) {
        List<Waiver> expired = waiverRepository.newlyExpired(Instant.now(), batchSize);
        for (Waiver waiver : expired) {
            auditLogger.record(
                    "system",
                    waiver.marketplace(),
                    EVENT_EXPIRED,
                    waiver.scope() == WaiverScope.SNAPSHOT ? waiver.scopeValue() : null,
                    "rule=%s; scope=%s:%s; approvedBy=%s; expired=%s"
                            .formatted(
                                    waiver.ruleId(),
                                    waiver.scope().stored(),
                                    waiver.scopeValue(),
                                    waiver.approvedBy(),
                                    waiver.expiresAt()));
            waiverRepository.markExpiryRecorded(waiver.id());
        }
        return expired.size();
    }

    /**
     * A path scope must name something, and must not climb out of the snapshot. {@code ..} is
     * refused rather than resolved: a waiver whose meaning depends on path arithmetic is a waiver
     * nobody can review by reading it.
     */
    private static String normalizePath(String scopeValue) {
        if (scopeValue == null || scopeValue.isBlank()) {
            throw new WaiverValidationException("a path-scoped waiver must name a path");
        }
        String value = scopeValue.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            throw new WaiverValidationException(
                    "a path-scoped waiver must name a path; the whole marketplace is not a scope");
        }
        for (String segment : value.split("/")) {
            if ("..".equals(segment)) {
                throw new WaiverValidationException("a waiver path must not contain '..'");
            }
        }
        return value;
    }
}

package dev.skillsgateway.server.approval;

import com.fasterxml.jackson.annotation.JsonValue;
import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.approval.ApprovalPostureRepository.StoredOverride;
import dev.skillsgateway.server.approval.ApprovalPostureRepository.StoredPosture;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A marketplace's approval posture and the override an external one rests on (GW_APPROVAL_0044.1,
 * GW_APPROVAL_0044.3, GW_APPROVAL_0044.4).
 *
 * <p>The posture is stored; whether it takes effect, its basis, is computed at every read from the
 * marketplace, the configured internal prefixes, the active override and the clock. Nothing that
 * can change underneath it — a prefix removed, an expiry passed — leaves a stale "trusted" bit.
 */
@Service
public class ApprovalPostureService {

    /**
     * How far ahead an {@code auto} posture's expiry may lie. The gateway's limit for every expiring
     * grant a named person makes, held in its own constant so that changing it is a decision about
     * this grant alone.
     */
    public static final Duration MAX_LIFETIME = Duration.ofDays(90);

    /** How far ahead an override's expiry may lie; matches the table's {@code CHECK}. */
    public static final Duration OVERRIDE_MAX_LIFETIME = Duration.ofDays(90);

    public static final String EVENT_POSTURE_CHANGED = "approval-posture-changed";
    public static final String EVENT_OVERRIDE_CREATED = "auto-approval-override-created";
    public static final String EVENT_OVERRIDE_REVOKED = "auto-approval-override-revoked";
    public static final String EVENT_OVERRIDE_EXPIRED = "auto-approval-override-expired";

    /** What a posture change is checked as, named in the conflict's ledger entry. */
    static final String GRANT = "approval-posture";

    public enum Posture {
        MANUAL,
        AUTO;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Why an {@code auto} posture takes effect, or that it does not ({@code none}). */
    public enum Basis {
        INTERNAL_PREFIX,
        HOSTED,
        OVERRIDE,
        AWAITING_SECOND_ADMINISTRATOR,
        NONE;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        public boolean effective() {
            return this == INTERNAL_PREFIX || this == HOSTED || this == OVERRIDE;
        }
    }

    @Schema(
            description = "An expiring exception that lets a marketplace outside the internal prefixes be approved"
                    + " automatically")
    public record OverrideRequest(
            @Schema(description = "Why this source is trusted; required")
            String reason,

            @Schema(description = "When the override lapses; required, at most 90 days ahead")
            Instant expiresAt) {}

    @Schema(description = "Set a marketplace's approval posture")
    public record PostureRequest(
            @Schema(
                    description = "manual or auto; required",
                    allowableValues = {"manual", "auto"})
            Posture posture,

            @Schema(description = "Why; required for auto") String reason,

            @Schema(description = "When auto lapses back to manual; required for auto, at most 90 days ahead")
            Instant expiresAt,

            @Schema(description = "For a marketplace outside the internal prefixes: the override auto rests on")
            OverrideRequest override) {}

    @Schema(description = "The active override")
    public record OverrideView(
            @Schema(description = "Override id") long id,

            @Schema(description = "Why this source is trusted")
            String reason,

            @Schema(description = "The administrator who created it")
            String createdBy,

            @Schema(description = "When it was created") Instant createdAt,
            @Schema(description = "When it lapses") Instant expiresAt) {}

    @Schema(description = "A marketplace's approval posture and whether it takes effect")
    public record PostureView(
            @Schema(
                    description = "The stored posture",
                    allowableValues = {"manual", "auto"})
            Posture posture,

            @Schema(
                    description = "Why auto takes effect, or none when it does not: manual, lapsed, or with no"
                            + " internal basis left. awaiting-second-administrator under four-eyes enforce, when"
                            + " the grantor registered the marketplace",
                    allowableValues = {"internal-prefix", "hosted", "override", "awaiting-second-administrator", "none"
                    })
            Basis basis,

            @Schema(description = "Who set the posture last")
            String setBy,

            @Schema(description = "When it was set") Instant setAt,
            @Schema(description = "Why") String reason,

            @Schema(description = "When auto lapses back to manual")
            Instant expiresAt,

            @Schema(description = "The active override, if any")
            OverrideView override) {}

    private final ApprovalPostureRepository repository;
    private final MarketplaceRepository marketplaceRepository;
    private final InternalSources internalSources;
    private final FourEyesGate fourEyesGate;
    private final AdminAuditLogger auditLogger;

    public ApprovalPostureService(
            ApprovalPostureRepository repository,
            MarketplaceRepository marketplaceRepository,
            InternalSources internalSources,
            FourEyesGate fourEyesGate,
            AdminAuditLogger auditLogger) {
        this.repository = repository;
        this.marketplaceRepository = marketplaceRepository;
        this.internalSources = internalSources;
        this.fourEyesGate = fourEyesGate;
        this.auditLogger = auditLogger;
    }

    @Requirements({"GW_APPROVAL_0044.1", "GW_APPROVAL_0044.3", "GW_APPROVAL_0044.4"})
    public PostureView view(String name) {
        Marketplace marketplace = find(name);
        return view(marketplace, Instant.now());
    }

    /** The basis an attempt would use right now, without writing anything. */
    @Requirements({"GW_APPROVAL_0044.1", "GW_APPROVAL_0044.3", "GW_APPROVAL_0044.4"})
    public Basis effectiveBasis(Marketplace marketplace) {
        Instant now = Instant.now();
        return basis(marketplace, repository.find(marketplace.id()), active(marketplace.id(), now), now);
    }

    /**
     * The basis for an automatic-approval attempt, recording a lapsed override first. The lapse is
     * written by whichever attempt reaches it first and by no other, so it is on the ledger once.
     */
    @Transactional
    @Requirements({"GW_APPROVAL_0044.3"})
    public Basis resolveForAttempt(Marketplace marketplace) {
        recordLapse(marketplace, Instant.now());
        return effectiveBasis(marketplace);
    }

    /**
     * Refuses an {@code auto} choice at registration before anything is created: it must be
     * complete, and the marketplace must be internal, since an override cannot be given there.
     */
    @Requirements({"GW_APPROVAL_0044.1"})
    public void checkRegistration(
            String name, String url, String origin, Posture posture, String reason, Instant expiresAt) {
        if (posture == null) {
            throw new PostureValidationException("approval.posture is required: manual or auto");
        }
        if (posture == Posture.MANUAL) {
            return;
        }
        validateAuto(reason, expiresAt, Instant.now());
        boolean hosted = Marketplace.ORIGIN_HOSTED.equals(origin);
        if (!hosted && !internalSources.isInternal(url)) {
            throw new NoInternalBasisException(name);
        }
    }

    @Transactional
    @Requirements({"GW_APPROVAL_0044.1", "GW_APPROVAL_0044.3", "GW_APPROVAL_0044.4"})
    public PostureView set(String name, PostureRequest request, String actor) {
        if (request == null || request.posture() == null) {
            throw new PostureValidationException("posture is required: manual or auto");
        }
        Marketplace marketplace = find(name);
        repository.lock(marketplace.id());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        recordLapse(marketplace, now);
        Optional<StoredOverride> open = repository.findOpen(marketplace.id());

        if (request.posture() == Posture.MANUAL) {
            String reason = blankToNull(request.reason());
            repository.save(marketplace.id(), new StoredPosture(Posture.MANUAL, actor, now, reason, null));
            open.ifPresent(override -> revoke(marketplace, override, actor, now));
            auditLogger.record(actor, marketplace, EVENT_POSTURE_CHANGED, null, "posture=manual");
            return view(marketplace, now);
        }

        validateAuto(request.reason(), request.expiresAt(), now);
        boolean internal = internalSources.isInternal(marketplace);
        Instant expiresAt = request.expiresAt();
        if (request.override() != null) {
            if (internal) {
                throw new PostureValidationException(
                        "marketplace '%s' is internal already; an override is only for a".formatted(marketplace.name())
                                + " marketplace outside the internal prefixes");
            }
            validateOverride(request.override(), now);
            open.ifPresent(override -> revoke(marketplace, override, actor, now));
            StoredOverride created = repository.createOverride(
                    marketplace.id(),
                    request.override().reason().trim(),
                    actor,
                    now,
                    request.override().expiresAt());
            auditLogger.record(
                    actor,
                    marketplace,
                    EVENT_OVERRIDE_CREATED,
                    null,
                    "override=%d; expires=%s; reason=%s"
                            .formatted(created.id(), created.expiresAt(), created.reason()));
            expiresAt = earliest(expiresAt, created.expiresAt());
        } else if (!internal) {
            StoredOverride standing = open.orElseThrow(() -> new NoInternalBasisException(marketplace.name()));
            expiresAt = earliest(expiresAt, standing.expiresAt());
        }

        String reason = request.reason().trim();
        repository.save(marketplace.id(), new StoredPosture(Posture.AUTO, actor, now, reason, expiresAt));
        PostureView view = view(marketplace, now);
        auditLogger.record(
                actor,
                marketplace,
                EVENT_POSTURE_CHANGED,
                null,
                "posture=auto; basis=%s; expires=%s; reason=%s"
                        .formatted(view.basis().wire(), expiresAt, reason));
        List<FourEyesConflictException.Conflict> conflicts = fourEyesGate.grantConflicts(marketplace, actor);
        if (!conflicts.isEmpty()) {
            auditLogger.record(
                    actor,
                    marketplace,
                    FourEyesGate.EVENT_CONFLICT,
                    null,
                    "on=%s; mode=%s; roles=%s; %s"
                            .formatted(
                                    GRANT,
                                    fourEyesGate.mode(),
                                    FourEyesGate.describe(conflicts),
                                    fourEyesGate.enforcing() ? "awaiting a second administrator" : "set"));
        }
        return view;
    }

    @Transactional
    @Requirements({"GW_APPROVAL_0044.3"})
    public PostureView revokeOverride(String name, String actor) {
        Marketplace marketplace = find(name);
        repository.lock(marketplace.id());
        Instant now = Instant.now();
        recordLapse(marketplace, now);
        StoredOverride open = repository
                .findOpen(marketplace.id())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "marketplace '%s' has no active override".formatted(name)));
        revoke(marketplace, open, actor, now);
        return view(marketplace, now);
    }

    private void revoke(Marketplace marketplace, StoredOverride override, String actor, Instant now) {
        if (repository.revoke(override.id(), actor, now)) {
            auditLogger.record(
                    actor, marketplace, EVENT_OVERRIDE_REVOKED, null, "override=%d".formatted(override.id()));
        }
    }

    private void recordLapse(Marketplace marketplace, Instant now) {
        Optional<StoredOverride> open = repository.findOpen(marketplace.id());
        if (open.isPresent()
                && !open.get().expiresAt().isAfter(now)
                && repository.recordExpired(open.get().id(), now)) {
            auditLogger.record(
                    WaiverService.SYSTEM_ACTOR,
                    marketplace,
                    EVENT_OVERRIDE_EXPIRED,
                    null,
                    "override=%d; expired=%s"
                            .formatted(open.get().id(), open.get().expiresAt()));
        }
    }

    private PostureView view(Marketplace marketplace, Instant now) {
        StoredPosture stored = repository.find(marketplace.id());
        Optional<StoredOverride> active = active(marketplace.id(), now);
        return new PostureView(
                stored.posture(),
                basis(marketplace, stored, active, now),
                stored.setBy(),
                stored.setAt(),
                stored.reason(),
                stored.expiresAt(),
                active.map(o -> new OverrideView(o.id(), o.reason(), o.createdBy(), o.createdAt(), o.expiresAt()))
                        .orElse(null));
    }

    private Optional<StoredOverride> active(long marketplaceId, Instant now) {
        return repository.findOpen(marketplaceId).filter(o -> o.expiresAt().isAfter(now));
    }

    private Basis basis(Marketplace marketplace, StoredPosture stored, Optional<StoredOverride> active, Instant now) {
        if (stored.posture() != Posture.AUTO
                || stored.expiresAt() == null
                || !stored.expiresAt().isAfter(now)) {
            return Basis.NONE;
        }
        if (fourEyesGate.enforcing()
                && !fourEyesGate.grantConflicts(marketplace, stored.setBy()).isEmpty()) {
            return Basis.AWAITING_SECOND_ADMINISTRATOR;
        }
        if (marketplace.hosted()) {
            return Basis.HOSTED;
        }
        if (internalSources.isInternal(marketplace.url())) {
            return Basis.INTERNAL_PREFIX;
        }
        return active.isPresent() ? Basis.OVERRIDE : Basis.NONE;
    }

    private static void validateAuto(String reason, Instant expiresAt, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new PostureValidationException(
                    "auto needs a reason: why this marketplace may be published" + " without a person");
        }
        requireExpiry("auto", expiresAt, now, MAX_LIFETIME);
    }

    private static void validateOverride(OverrideRequest override, Instant now) {
        if (override.reason() == null || override.reason().isBlank()) {
            throw new PostureValidationException("an override needs a reason: why this source is trusted");
        }
        requireExpiry("an override", override.expiresAt(), now, OVERRIDE_MAX_LIFETIME);
    }

    private static void requireExpiry(String what, Instant expiresAt, Instant now, Duration limit) {
        if (expiresAt == null) {
            throw new PostureValidationException(what + " needs an expiry; there is no way to say forever");
        }
        if (!expiresAt.isAfter(now)) {
            throw new PostureValidationException(what + "'s expiry must be in the future");
        }
        if (expiresAt.isAfter(now.plus(limit))) {
            throw new PostureValidationException(
                    "%s may run for at most %d days; renew it rather than extend it".formatted(what, limit.toDays()));
        }
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Marketplace find(String name) {
        return marketplaceRepository
                .findByName(name)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(name)));
    }
}

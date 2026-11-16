package dev.skillsgateway.server.approval;

import com.fasterxml.jackson.annotation.JsonValue;
import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.approval.AutoApprovalSettingRepository.StoredExemption;
import dev.skillsgateway.server.approval.AutoApprovalSettingRepository.StoredSetting;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.estate.EstateReconciler;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A marketplace's automatic approval and the administrator exemption an external one rests on
 * (GW_APPROVAL_0044.1, GW_APPROVAL_0044.3, GW_APPROVAL_0044.4).
 *
 * <p>Whether it is on is stored; whether it takes effect, and what allows it, is computed at every
 * read from the marketplace, the configured internal prefixes, the active exemption and the clock.
 * Nothing that can change underneath it — a prefix removed, an expiry passed — leaves a stale
 * "trusted" bit.
 */
@Service
public class AutoApprovalSettingService {

    /**
     * How far ahead the expiry of automatic approval may lie. The gateway's limit for every expiring
     * grant a named person makes, held in its own constant so that changing it is a decision about
     * this grant alone.
     */
    public static final Duration MAX_LIFETIME = Duration.ofDays(90);

    /** How far ahead an exemption's expiry may lie; matches the table's {@code CHECK}. */
    public static final Duration EXEMPTION_MAX_LIFETIME = Duration.ofDays(90);

    public static final String EVENT_CHANGED = "auto-approval-changed";
    public static final String EVENT_EXEMPTION_GRANTED = "auto-approval-exemption-granted";
    public static final String EVENT_EXEMPTION_REVOKED = "auto-approval-exemption-revoked";
    public static final String EVENT_EXEMPTION_EXPIRED = "auto-approval-exemption-expired";

    /** What turning automatic approval on is checked as, named in the conflict's ledger entry. */
    static final String GRANT = "auto-approval";

    /** Why automatic approval takes effect, or that it does not ({@code none}). */
    public enum AllowedBy {
        INTERNAL_SOURCE,
        HOSTED,
        EXEMPTION,
        AWAITING_SECOND_ADMINISTRATOR,
        NONE;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        public boolean effective() {
            return this == INTERNAL_SOURCE || this == HOSTED || this == EXEMPTION;
        }
    }

    @Schema(
            name = "AutoApprovalExemptionRequest",
            description = "An expiring administrator exemption that lets a marketplace outside the internal prefixes"
                    + " be approved automatically")
    public record ExemptionRequest(
            @Schema(description = "Why this source is trusted; required")
            String reason,

            @Schema(description = "When the exemption lapses; required, at most 90 days ahead")
            Instant expiresAt) {}

    @Schema(name = "AutoApprovalRequest", description = "Turn a marketplace's automatic approval on or off")
    public record SettingRequest(
            @Schema(description = "true or false; required") Boolean enabled,

            @Schema(description = "Why; required to turn it on")
            String reason,

            @Schema(description = "When it lapses back to off; required to turn it on, at most 90 days ahead")
            Instant expiresAt,

            @Schema(description = "For a marketplace outside the internal prefixes: the exemption it rests on")
            ExemptionRequest exemption) {}

    @Schema(name = "AutoApprovalExemptionView", description = "The active administrator exemption")
    public record ExemptionView(
            @Schema(description = "Exemption id") long id,

            @Schema(description = "Why this source is trusted")
            String reason,

            @Schema(description = "The administrator who granted it")
            String createdBy,

            @Schema(description = "When it was granted") Instant createdAt,
            @Schema(description = "When it lapses") Instant expiresAt) {}

    @Schema(name = "AutoApprovalView", description = "A marketplace's automatic approval and whether it takes effect")
    public record SettingView(
            @Schema(description = "Whether automatic approval is turned on")
            boolean enabled,

            @Schema(
                    description = "Why it takes effect, or none when it does not: off, lapsed, or not internal"
                            + " and with no exemption left. awaiting-second-administrator under four-eyes enforce,"
                            + " when the grantor registered the marketplace",
                    allowableValues = {"internal-source", "hosted", "exemption", "awaiting-second-administrator", "none"
                    })
            AllowedBy allowedBy,

            @Schema(description = "Who set it last") String setBy,

            @Schema(description = "When it was set") Instant setAt,
            @Schema(description = "Why") String reason,

            @Schema(description = "When it lapses back to off")
            Instant expiresAt,

            @Schema(description = "The active exemption, if any")
            ExemptionView exemption,

            @Schema(
                    description = "The gateway's automatic approval mode. Automatic approval publishes only under"
                            + " on; under dry-run it is evaluated and recorded, and under off it does nothing",
                    allowableValues = {"off", "dry-run", "on"})
            SkillsGatewayProperties.AutoMode mode) {}

    private final AutoApprovalSettingRepository repository;
    private final MarketplaceRepository marketplaceRepository;
    private final InternalSources internalSources;
    private final FourEyesGate fourEyesGate;
    private final AdminAuditLogger auditLogger;
    private final SkillsGatewayProperties properties;

    public AutoApprovalSettingService(
            AutoApprovalSettingRepository repository,
            MarketplaceRepository marketplaceRepository,
            InternalSources internalSources,
            FourEyesGate fourEyesGate,
            AdminAuditLogger auditLogger,
            SkillsGatewayProperties properties) {
        this.properties = properties;
        this.repository = repository;
        this.marketplaceRepository = marketplaceRepository;
        this.internalSources = internalSources;
        this.fourEyesGate = fourEyesGate;
        this.auditLogger = auditLogger;
    }

    @Requirements({"GW_APPROVAL_0044.1", "GW_APPROVAL_0044.3", "GW_APPROVAL_0044.4"})
    public SettingView view(String name) {
        Marketplace marketplace = find(name);
        return view(marketplace, Instant.now());
    }

    /** What would allow an attempt right now, without writing anything. */
    @Requirements({"GW_APPROVAL_0044.1", "GW_APPROVAL_0044.3", "GW_APPROVAL_0044.4"})
    public AllowedBy effectiveAllowedBy(Marketplace marketplace) {
        Instant now = Instant.now();
        return allowedBy(marketplace, repository.find(marketplace.id()), active(marketplace.id(), now), now);
    }

    /**
     * What allows an automatic-approval attempt, recording a lapsed exemption first. The lapse is
     * written by whichever attempt reaches it first and by no other, so it is on the ledger once.
     */
    @Transactional
    @Requirements({"GW_APPROVAL_0044.3"})
    public AllowedBy resolveForAttempt(Marketplace marketplace) {
        recordLapse(marketplace, Instant.now());
        return effectiveAllowedBy(marketplace);
    }

    /**
     * Refuses turning automatic approval on at registration before anything is created: the choice
     * must be complete, and the marketplace must be internal, since an exemption cannot be given there.
     */
    @Requirements({"GW_APPROVAL_0044.1"})
    public void checkRegistration(
            String name, String url, String origin, Boolean enabled, String reason, Instant expiresAt) {
        if (enabled == null) {
            throw new AutoApprovalSettingValidationException("autoApproval.enabled is required: true or false");
        }
        if (!enabled) {
            return;
        }
        validateOn(reason, expiresAt, Instant.now());
        boolean hosted = Marketplace.ORIGIN_HOSTED.equals(origin);
        if (!hosted && !internalSources.isInternal(url)) {
            throw new NotInternalException(name);
        }
    }

    /**
     * Turns automatic approval off because its approvals were withdrawn: left on, the next push
     * would be approved the same way. Nothing happens to a marketplace that is off already.
     *
     * @return whether it was on and is now off
     */
    @Transactional
    @Requirements({"GW_APPROVAL_0044.14"})
    public boolean turnOffAfterWithdrawal(String name, String actor, String reason) {
        Marketplace marketplace = find(name);
        repository.lock(marketplace.id());
        if (!repository.find(marketplace.id()).enabled()) {
            return false;
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        recordLapse(marketplace, now);
        repository.save(marketplace.id(), new StoredSetting(false, actor, now, reason, null));
        repository.findOpen(marketplace.id()).ifPresent(exemption -> revoke(marketplace, exemption, actor, now));
        auditLogger.record(
                actor, marketplace, EVENT_CHANGED, null, "enabled=false (its automatic approvals were withdrawn)");
        return true;
    }

    /** A setting declared in the estate, applied as the reconciler. */
    @Transactional
    @Requirements({"GW_ESTATE_0006", "GW_APPROVAL_0044.4"})
    public SettingView setDeclared(String name, SettingRequest request) {
        return apply(name, request, EstateReconciler.ACTOR);
    }

    @Transactional
    @Requirements({"GW_APPROVAL_0044.1", "GW_APPROVAL_0044.3", "GW_APPROVAL_0044.4"})
    public SettingView set(String name, SettingRequest request, String actor) {
        // The reconciler's grant needs no second administrator, so its name must not be reachable by sign-in.
        if (EstateReconciler.ACTOR.equals(actor)) {
            throw new AccessDeniedException(
                    "'%s' is the estate reconciler's identity; declare the setting in the estate instead"
                            .formatted(actor));
        }
        return apply(name, request, actor);
    }

    private SettingView apply(String name, SettingRequest request, String actor) {
        if (request == null || request.enabled() == null) {
            throw new AutoApprovalSettingValidationException("enabled is required: true or false");
        }
        Marketplace marketplace = find(name);
        repository.lock(marketplace.id());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        recordLapse(marketplace, now);
        Optional<StoredExemption> open = repository.findOpen(marketplace.id());

        if (!request.enabled()) {
            String reason = blankToNull(request.reason());
            repository.save(marketplace.id(), new StoredSetting(false, actor, now, reason, null));
            open.ifPresent(exemption -> revoke(marketplace, exemption, actor, now));
            auditLogger.record(actor, marketplace, EVENT_CHANGED, null, "enabled=false");
            return view(marketplace, now);
        }

        validateOn(request.reason(), request.expiresAt(), now);
        boolean internal = internalSources.isInternal(marketplace);
        Instant expiresAt = request.expiresAt();
        if (request.exemption() != null) {
            if (internal) {
                throw new AutoApprovalSettingValidationException(
                        "marketplace '%s' is internal already; an exemption is only for a".formatted(marketplace.name())
                                + " marketplace outside the internal prefixes");
            }
            validateExemption(request.exemption(), now);
            open.ifPresent(exemption -> revoke(marketplace, exemption, actor, now));
            StoredExemption created = repository.createExemption(
                    marketplace.id(),
                    request.exemption().reason().trim(),
                    actor,
                    now,
                    request.exemption().expiresAt());
            auditLogger.record(
                    actor,
                    marketplace,
                    EVENT_EXEMPTION_GRANTED,
                    null,
                    "exemption=%d; expires=%s; reason=%s"
                            .formatted(created.id(), created.expiresAt(), created.reason()));
            expiresAt = earliest(expiresAt, created.expiresAt());
        } else if (!internal) {
            StoredExemption standing = open.orElseThrow(() -> new NotInternalException(marketplace.name()));
            expiresAt = earliest(expiresAt, standing.expiresAt());
        }

        String reason = request.reason().trim();
        repository.save(marketplace.id(), new StoredSetting(true, actor, now, reason, expiresAt));
        SettingView view = view(marketplace, now);
        auditLogger.record(
                actor,
                marketplace,
                EVENT_CHANGED,
                null,
                "enabled=true; allowedBy=%s; expires=%s; reason=%s"
                        .formatted(view.allowedBy().wire(), expiresAt, reason));
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
    public SettingView revokeExemption(String name, String actor) {
        Marketplace marketplace = find(name);
        repository.lock(marketplace.id());
        Instant now = Instant.now();
        recordLapse(marketplace, now);
        StoredExemption open = repository
                .findOpen(marketplace.id())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "marketplace '%s' has no active exemption".formatted(name)));
        revoke(marketplace, open, actor, now);
        return view(marketplace, now);
    }

    private void revoke(Marketplace marketplace, StoredExemption exemption, String actor, Instant now) {
        if (repository.revoke(exemption.id(), actor, now)) {
            auditLogger.record(
                    actor, marketplace, EVENT_EXEMPTION_REVOKED, null, "exemption=%d".formatted(exemption.id()));
        }
    }

    private void recordLapse(Marketplace marketplace, Instant now) {
        Optional<StoredExemption> open = repository.findOpen(marketplace.id());
        if (open.isPresent()
                && !open.get().expiresAt().isAfter(now)
                && repository.recordExpired(open.get().id(), now)) {
            auditLogger.record(
                    WaiverService.SYSTEM_ACTOR,
                    marketplace,
                    EVENT_EXEMPTION_EXPIRED,
                    null,
                    "exemption=%d; expired=%s"
                            .formatted(open.get().id(), open.get().expiresAt()));
        }
    }

    private SettingView view(Marketplace marketplace, Instant now) {
        StoredSetting stored = repository.find(marketplace.id());
        Optional<StoredExemption> active = active(marketplace.id(), now);
        return new SettingView(
                stored.enabled(),
                allowedBy(marketplace, stored, active, now),
                stored.setBy(),
                stored.setAt(),
                stored.reason(),
                stored.expiresAt(),
                active.map(e -> new ExemptionView(e.id(), e.reason(), e.createdBy(), e.createdAt(), e.expiresAt()))
                        .orElse(null),
                properties.approval().auto().mode());
    }

    private Optional<StoredExemption> active(long marketplaceId, Instant now) {
        return repository.findOpen(marketplaceId).filter(e -> e.expiresAt().isAfter(now));
    }

    private AllowedBy allowedBy(
            Marketplace marketplace, StoredSetting stored, Optional<StoredExemption> active, Instant now) {
        if (!stored.enabled()
                || stored.expiresAt() == null
                || !stored.expiresAt().isAfter(now)) {
            return AllowedBy.NONE;
        }
        if (fourEyesGate.enforcing()
                && !fourEyesGate.grantConflicts(marketplace, stored.setBy()).isEmpty()) {
            return AllowedBy.AWAITING_SECOND_ADMINISTRATOR;
        }
        if (marketplace.hosted()) {
            return AllowedBy.HOSTED;
        }
        if (internalSources.isInternal(marketplace.url())) {
            return AllowedBy.INTERNAL_SOURCE;
        }
        return active.isPresent() ? AllowedBy.EXEMPTION : AllowedBy.NONE;
    }

    private static void validateOn(String reason, Instant expiresAt, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new AutoApprovalSettingValidationException(
                    "automatic approval needs a reason: why this marketplace may be published" + " without a person");
        }
        requireExpiry("automatic approval", expiresAt, now, MAX_LIFETIME);
    }

    private static void validateExemption(ExemptionRequest exemption, Instant now) {
        if (exemption.reason() == null || exemption.reason().isBlank()) {
            throw new AutoApprovalSettingValidationException("an exemption needs a reason: why this source is trusted");
        }
        requireExpiry("an exemption", exemption.expiresAt(), now, EXEMPTION_MAX_LIFETIME);
    }

    private static void requireExpiry(String what, Instant expiresAt, Instant now, Duration limit) {
        if (expiresAt == null) {
            throw new AutoApprovalSettingValidationException(what + " needs an expiry; there is no way to say forever");
        }
        if (!expiresAt.isAfter(now)) {
            throw new AutoApprovalSettingValidationException(what + "'s expiry must be in the future");
        }
        if (expiresAt.isAfter(now.plus(limit))) {
            throw new AutoApprovalSettingValidationException(
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

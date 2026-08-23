package dev.skillsgateway.server.estate;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.admin.MarketplaceRegistrationService;
import dev.skillsgateway.server.audit.AuditExportService;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredAuditSink;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredGrant;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredMarketplace;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredPolicyRule;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredWebhook;
import dev.skillsgateway.server.config.SkillsGatewayProperties.Estate;
import dev.skillsgateway.server.estate.EstateReconciliation.Entry;
import dev.skillsgateway.server.persistence.AuditSink;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriber;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import dev.skillsgateway.server.policy.PolicyRule;
import dev.skillsgateway.server.policy.PolicyRuleService;
import dev.skillsgateway.server.roles.RoleService;
import dev.skillsgateway.server.sync.SyncService;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Converges the running estate to the declared one (GW_0083–GW_0087): additively — an object
 * absent from the declaration is never deleted, deregistered or revoked — and idempotently — a
 * converged estate reconciles with zero writes and zero ledger entries. Every applied change goes
 * through the same validated, audited service path as its API equivalent, attributed to
 * {@link #ACTOR} so ledger consumers can tell declarative from interactive changes.
 */
@Service
public class EstateReconciler {

    private static final Logger log = LoggerFactory.getLogger(EstateReconciler.class);

    /** The reconciler's ledger identity: how declarative changes are told from interactive ones. */
    public static final String ACTOR = "config-reconciler";

    /** Sync modes a declaration may set; webhook mode's show-once HMAC secret is API-only (GW_0084). */
    private static final Set<String> DECLARABLE_SYNC_MODES =
            Set.of(Marketplace.SYNC_ON_DEMAND, Marketplace.SYNC_SCHEDULED);

    /** Below this, an operator-supplied HMAC key is a typo, not a secret (GW_0086). */
    private static final int MIN_SECRET_LENGTH = 16;

    /** Failure entries are not tied to a marketplace; the ledger column is NOT NULL. */
    private static final String NO_MARKETPLACE = "-";

    private final MarketplaceRegistrationService registrationService;
    private final MarketplaceRepository marketplaceRepository;
    private final SyncService syncService;
    private final RoleService roleService;
    private final WebhookService webhookService;
    private final WebhookSubscriberRepository subscriberRepository;
    private final AuditExportService auditExportService;
    private final PolicyRuleService policyRuleService;
    private final AdminAuditLogger auditLogger;
    private final AtomicReference<EstateReconciliation> lastRun = new AtomicReference<>();

    public EstateReconciler(
            MarketplaceRegistrationService registrationService,
            MarketplaceRepository marketplaceRepository,
            SyncService syncService,
            RoleService roleService,
            WebhookService webhookService,
            WebhookSubscriberRepository subscriberRepository,
            AuditExportService auditExportService,
            PolicyRuleService policyRuleService,
            AdminAuditLogger auditLogger) {
        this.registrationService = registrationService;
        this.marketplaceRepository = marketplaceRepository;
        this.syncService = syncService;
        this.roleService = roleService;
        this.webhookService = webhookService;
        this.subscriberRepository = subscriberRepository;
        this.auditExportService = auditExportService;
        this.policyRuleService = policyRuleService;
        this.auditLogger = auditLogger;
    }

    /**
     * One reconciliation pass: marketplaces, then grants (so a grant may reference a marketplace
     * declared in the same configuration), then webhook subscribers, then audit sinks.
     * Synchronized so startup and the on-demand endpoint can never interleave; the database's
     * unique constraints backstop any other writer. Entry validation failures are isolated and
     * reported (GW_0087); infrastructure failures propagate — a broken declaration must not brick
     * the gateway, but a broken database must not be papered over.
     */
    @Requirements({"GW_0083", "GW_0087"})
    public synchronized EstateReconciliation reconcile(Estate estate, String trigger) {
        List<Entry> entries = new ArrayList<>();
        for (DeclaredMarketplace declared : estate.marketplaces()) {
            entries.add(isolated("marketplace", declared.name(), () -> reconcileMarketplace(declared)));
        }
        for (DeclaredGrant declared : estate.grants()) {
            String name = "%s/%s/%s"
                    .formatted(declared.principal(), declared.role(), Objects.toString(declared.marketplace(), "-"));
            entries.add(isolated("grant", name, () -> reconcileGrant(declared)));
        }
        for (DeclaredWebhook declared : estate.webhooks()) {
            entries.add(isolated("webhook", declared.name(), () -> reconcileWebhook(declared)));
        }
        for (DeclaredAuditSink declared : estate.auditSinks()) {
            entries.add(isolated("audit-sink", declared.name(), () -> reconcileSink(declared)));
        }
        for (DeclaredPolicyRule declared : estate.policyRules()) {
            entries.add(isolated("policy-rule", declared.name(), () -> reconcilePolicyRule(declared)));
        }
        EstateReconciliation run = EstateReconciliation.of(trigger, entries);
        lastRun.set(run);
        if (run.failed() > 0) {
            log.error(
                    "estate reconciliation ({}): {} created, {} updated, {} unchanged, {} FAILED",
                    trigger,
                    run.created(),
                    run.updated(),
                    run.unchanged(),
                    run.failed());
        } else if (run.created() + run.updated() > 0) {
            log.info(
                    "estate reconciliation ({}): {} created, {} updated, {} unchanged",
                    trigger,
                    run.created(),
                    run.updated(),
                    run.unchanged());
        }
        return run;
    }

    /** The most recent run, startup included; empty only before the first run ever completes. */
    public Optional<EstateReconciliation> lastRun() {
        return Optional.ofNullable(lastRun.get());
    }

    /**
     * Runs one entry, isolating validation failures: the entry fails loudly — ERROR log and a
     * ledger record per run, because an unconvergeable declaration is drift an operator must see —
     * while the rest of the declaration still converges. Only {@link ResponseStatusException}
     * (what every shared validation path throws) is a validation failure; anything else is
     * infrastructure and propagates.
     */
    @Requirements({"GW_0087"})
    private Entry isolated(String kind, String name, EntryReconciliation reconciliation) {
        try {
            return reconciliation.run();
        } catch (ResponseStatusException e) {
            String reason = Objects.toString(e.getReason(), e.getMessage());
            log.error("estate reconciliation: {} '{}' failed: {}", kind, name, reason);
            auditLogger.record(
                    ACTOR,
                    NO_MARKETPLACE,
                    "estate-reconciliation-failed",
                    null,
                    "kind=%s name=%s reason=%s".formatted(kind, name, reason));
            return Entry.failed(kind, name, reason);
        }
    }

    /**
     * A declared marketplace enters through the exact registration gate the API uses (GW_0084):
     * name rules, reserved catalog name, URL scheme allowlist. The declaration has no ref field,
     * so the gateway-pinned ref (GW_0017) cannot be overridden; a stored URL that differs from the
     * declared one is a failure, never an update — the API deliberately has no URL update, and the
     * reconciler must not acquire a power the API refuses to have.
     */
    @Requirements({"GW_0084"})
    private Entry reconcileMarketplace(DeclaredMarketplace declared) {
        String mode = declared.syncMode();
        if (mode != null && !DECLARABLE_SYNC_MODES.contains(mode)) {
            throw validationFailure("sync-mode must be one of %s; webhook mode's inbound secret is"
                    + " generated and shown once, so it cannot be declared".formatted(DECLARABLE_SYNC_MODES));
        }
        Optional<Marketplace> existing = marketplaceRepository.findByName(declared.name());
        if (existing.isEmpty()) {
            registrationService.register(
                    declared.name(), declared.url(), declared.origin(), declared.pushPolicy(), ACTOR);
            if (mode != null && !Marketplace.SYNC_ON_DEMAND.equals(mode)) {
                syncService.changeMode(declared.name(), mode, ACTOR);
            }
            return Entry.created("marketplace", declared.name(), null);
        }
        Marketplace stored = existing.get();
        // Null-safe on both sides: a hosted marketplace has no url at all (GW_0101), and declaring
        // one for it — or dropping the one an upstream marketplace was registered with — is the
        // same supply-chain swap the immutability rule exists to refuse.
        if (!Objects.equals(stored.url(), declared.url())) {
            throw validationFailure("declared url differs from the registered upstream; a marketplace URL is immutable"
                    + " because changing it would swap the supply chain under approved snapshots");
        }
        String declaredOrigin = declared.origin() == null ? Marketplace.ORIGIN_UPSTREAM : declared.origin();
        if (!declaredOrigin.equals(stored.origin())) {
            throw validationFailure("declared origin '%s' differs from the registered '%s'; where a marketplace's"
                            .formatted(declaredOrigin, stored.origin())
                    + " content comes from is immutable for the same reason its url is");
        }
        if (mode != null && !mode.equals(stored.syncMode())) {
            syncService.changeMode(declared.name(), mode, ACTOR);
            return Entry.updated("marketplace", declared.name(), "sync-mode=" + mode);
        }
        return Entry.unchanged("marketplace", declared.name());
    }

    /**
     * A declared grant goes through the same validation and audited insert as the grants API
     * (GW_0085); an identical existing grant — declared or interactively created — is converged
     * state, so the reconciler checks before it writes and a duplicate is unchanged, not an error.
     */
    @Requirements({"GW_0085"})
    private Entry reconcileGrant(DeclaredGrant declared) {
        String name = "%s/%s/%s"
                .formatted(declared.principal(), declared.role(), Objects.toString(declared.marketplace(), "-"));
        boolean exists = declared.principal() != null
                && roleService.rolesOf(declared.principal()).stream()
                        .anyMatch(role -> Objects.equals(role.role(), declared.role())
                                && Objects.equals(role.marketplace(), declared.marketplace()));
        if (exists) {
            return Entry.unchanged("grant", name);
        }
        roleService.grant(declared.principal(), declared.role(), declared.marketplace(), ACTOR);
        return Entry.created("grant", name, null);
    }

    /**
     * A declared subscriber is created through the same path as the webhooks API, with the
     * operator-supplied secret in place of a generated one (GW_0086); an existing one converges
     * url, event filter and secret in place — rotation is a config edit away, audited without the
     * value. Update targets are re-validated against the same scheme allowlist as creation.
     */
    @Requirements({"GW_0086"})
    private Entry reconcileWebhook(DeclaredWebhook declared) {
        requireUsableSecret(declared.secret());
        Optional<WebhookSubscriber> existing = webhookService.findSubscriber(declared.name());
        if (existing.isEmpty()) {
            webhookService.register(declared.name(), declared.url(), declared.events(), declared.secret(), ACTOR);
            return Entry.created("webhook", declared.name(), null);
        }
        WebhookSubscriber stored = existing.get();
        String events = WebhookService.normalizeEvents(declared.events());
        List<String> changes = new ArrayList<>();
        if (!stored.url().equals(declared.url())) {
            changes.add("url");
        }
        if (!stored.events().equals(events)) {
            changes.add("events");
        }
        if (!stored.secret().equals(declared.secret())) {
            changes.add("secret");
        }
        if (changes.isEmpty()) {
            return Entry.unchanged("webhook", declared.name());
        }
        webhookService.validateTarget(declared.url());
        subscriberRepository.update(stored.id(), declared.url(), declared.secret(), events);
        String detail = "changed=" + String.join(",", changes);
        auditLogger.record(ACTOR, NO_MARKETPLACE, "webhook-subscriber-updated", null, detail);
        return Entry.updated("webhook", declared.name(), detail);
    }

    /**
     * A declared sink is created through the same path as the audit API, with the operator's
     * secret (GW_0086). An existing one converges the target URL, the secret (via its delivery
     * channel, a webhook subscriber) and the batch size; {@code after} seeds the cursor at
     * creation only — the cursor is runtime progress, and re-applying it would re-deliver the
     * ledger on every deploy.
     */
    @Requirements({"GW_0086"})
    private Entry reconcileSink(DeclaredAuditSink declared) {
        requireUsableSecret(declared.secret());
        Optional<AuditSink> existing = auditExportService.findSinkByName(declared.name());
        if (existing.isEmpty()) {
            auditExportService.registerSink(
                    declared.name(), declared.url(), declared.after(), declared.batchSize(), declared.secret(), ACTOR);
            return Entry.created("audit-sink", declared.name(), null);
        }
        AuditSink stored = existing.get();
        WebhookSubscriber channel = subscriberRepository
                .findById(stored.subscriberId())
                .orElseThrow(() -> validationFailure("sink '%s' has no delivery channel".formatted(declared.name())));
        List<String> changes = new ArrayList<>();
        if (!channel.url().equals(declared.url())) {
            changes.add("url");
        }
        if (!channel.secret().equals(declared.secret())) {
            changes.add("secret");
        }
        boolean batchSizeChanged = declared.batchSize() != null && declared.batchSize() != stored.batchSize();
        if (batchSizeChanged) {
            changes.add("batch-size");
        }
        if (changes.isEmpty()) {
            return Entry.unchanged("audit-sink", declared.name());
        }
        if (changes.contains("url") || changes.contains("secret")) {
            webhookService.validateTarget(declared.url());
            subscriberRepository.update(channel.id(), declared.url(), declared.secret(), channel.events());
        }
        if (batchSizeChanged) {
            auditExportService.updateSinkBatchSize(stored.id(), declared.batchSize());
        }
        String detail = "changed=" + String.join(",", changes);
        auditLogger.record(ACTOR, NO_MARKETPLACE, "audit-sink-updated", null, detail);
        return Entry.updated("audit-sink", declared.name(), detail);
    }

    /**
     * A declared policy rule goes through the same compiled, audited lifecycle path as the policy
     * API (GW_0089): an expression that does not compile to a boolean is an isolated entry
     * failure, an identical stored rule converges with zero writes, and drift in expression,
     * description or the enabled flag is an update through the service — never a direct write.
     */
    @Requirements({"GW_0089"})
    private Entry reconcilePolicyRule(DeclaredPolicyRule declared) {
        boolean enabled = declared.enabled() == null || declared.enabled();
        Optional<PolicyRule> existing = policyRuleService.find(declared.name());
        if (existing.isEmpty()) {
            policyRuleService.create(declared.name(), declared.description(), declared.expression(), enabled, ACTOR);
            return Entry.created("policy-rule", declared.name(), null);
        }
        PolicyRule stored = existing.get();
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(stored.expression(), declared.expression())) {
            changes.add("expression");
        }
        if (!Objects.equals(stored.description(), declared.description())) {
            changes.add("description");
        }
        if (stored.enabled() != enabled) {
            changes.add("enabled");
        }
        if (changes.isEmpty()) {
            return Entry.unchanged("policy-rule", declared.name());
        }
        policyRuleService.update(declared.name(), declared.description(), declared.expression(), enabled, ACTOR);
        return Entry.updated("policy-rule", declared.name(), "changed=" + String.join(",", changes));
    }

    /** A blank or trivially short HMAC key would silently weaken every signature (GW_0086). */
    @Requirements({"GW_0086"})
    private static void requireUsableSecret(String secret) {
        if (secret == null || secret.isBlank() || secret.length() < MIN_SECRET_LENGTH) {
            throw validationFailure("a declared secret must be at least %d characters; supply it by"
                    + " environment-variable reference, never inline".formatted(MIN_SECRET_LENGTH));
        }
    }

    /** Entry failures reuse the status type every shared validation path throws (see isolated). */
    private static ResponseStatusException validationFailure(String reason) {
        return new ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, reason);
    }

    @FunctionalInterface
    private interface EntryReconciliation {
        Entry run();
    }
}

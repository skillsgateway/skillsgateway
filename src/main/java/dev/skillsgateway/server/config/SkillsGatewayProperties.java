package dev.skillsgateway.server.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skills-gateway")
public record SkillsGatewayProperties(
        Path dataDir,
        List<String> allowedUrlSchemes,
        Boolean devInsecureAuth,
        Webhooks webhooks,
        AuditExport auditExport,
        Retention retention,
        Vetting vetting,
        Sync sync,
        Catalog catalog,
        Tokens tokens,
        Roles roles,
        Oidc oidc,
        Estate estate) {

    public SkillsGatewayProperties {
        if (dataDir == null) {
            dataDir = Path.of("data");
        }
        if (allowedUrlSchemes == null || allowedUrlSchemes.isEmpty()) {
            allowedUrlSchemes = List.of("http", "https");
        }
        if (devInsecureAuth == null) {
            devInsecureAuth = false;
        }
        if (webhooks == null) {
            webhooks = new Webhooks(null, null, null, null, null, null, null);
        }
        if (auditExport == null) {
            auditExport = new AuditExport(null, null, null, null, null, null);
        }
        if (retention == null) {
            retention = new Retention(null, null, null, null, null, null);
        }
        if (vetting == null) {
            vetting = new Vetting(null, null, null, null, null, null, null);
        }
        if (sync == null) {
            sync = new Sync(null, null, null, null);
        }
        if (catalog == null) {
            catalog = new Catalog(null, null);
        }
        if (tokens == null) {
            tokens = new Tokens(null);
        }
        if (roles == null) {
            roles = new Roles(null, null, null, null);
        }
        if (oidc == null) {
            oidc = new Oidc(null);
        }
        if (estate == null) {
            estate = new Estate(null, null, null, null, null);
        }
    }

    /**
     * The declarative estate (GW_0083–GW_0087): marketplaces, role grants, webhook subscribers and
     * audit export sinks defined as configuration and reconciled — additively, idempotently — at
     * startup and on demand. Everything here defaults to empty, and an empty declaration reconciles
     * nothing, so the block's absence is exactly today's behavior.
     *
     * <p>Personal access tokens are deliberately absent: they are user-owned credentials, API-only
     * by design. So is a prune/authoritative mode: an object missing from this declaration is never
     * deleted, deregistered or revoked by reconciliation.
     */
    public record Estate(
            List<DeclaredMarketplace> marketplaces,
            List<DeclaredGrant> grants,
            List<DeclaredWebhook> webhooks,
            List<DeclaredAuditSink> auditSinks,
            List<DeclaredPolicyRule> policyRules) {

        public Estate {
            marketplaces = marketplaces == null ? List.of() : List.copyOf(marketplaces);
            grants = grants == null ? List.of() : List.copyOf(grants);
            webhooks = webhooks == null ? List.of() : List.copyOf(webhooks);
            auditSinks = auditSinks == null ? List.of() : List.copyOf(auditSinks);
            policyRules = policyRules == null ? List.of() : List.copyOf(policyRules);
        }

        public boolean isEmpty() {
            return marketplaces.isEmpty()
                    && grants.isEmpty()
                    && webhooks.isEmpty()
                    && auditSinks.isEmpty()
                    && policyRules.isEmpty();
        }
    }

    /**
     * A declared marketplace (GW_0084). There is deliberately no ref field: the ingested ref is the
     * gateway's decision (GW_0017), so the declaration cannot express one.
     *
     * @param name gateway-local marketplace name, same rules as the API
     * @param url upstream clone URL; its scheme must be on the allowlist, and once registered it is
     *     immutable — a differing declared URL is a reconciliation failure, never an update
     * @param syncMode {@code on-demand} or {@code scheduled}; {@code webhook} is refused (its inbound
     *     HMAC secret is gateway-generated show-once, which has no declarative form). Null means the
     *     stored mode is not managed and never touched. A hosted marketplace accepts only
     *     {@code on-demand}: its ingestion trigger is the push.
     * @param origin {@code upstream} (the default) or {@code hosted} (GW_0101); a hosted marketplace
     *     declares no url, and like a url the origin is immutable after registration
     * @param pushPolicy for a hosted marketplace, {@code append-only} (the default) or
     *     {@code allow-rewrite}
     */
    public record DeclaredMarketplace(String name, String url, String syncMode, String origin, String pushPolicy) {}

    /**
     * A declared role grant (GW_0085), the exact shape of the grants API: approver grants name one
     * marketplace that must exist at reconcile time (declared here or API-registered); admin and
     * auditor grants must not name one.
     */
    public record DeclaredGrant(String principal, String role, String marketplace) {}

    /**
     * A declared webhook subscriber (GW_0086). The signing secret is operator-supplied — reference
     * an environment variable ({@code ${...}}) rather than inlining a literal — and write-only:
     * never logged, never audited, never answered by any API. Changing the referenced value rotates
     * the stored secret idempotently.
     *
     * @param events comma-delimited event filter, or null/blank for every event
     */
    public record DeclaredWebhook(String name, String url, String events, String secret) {}

    /**
     * A declared audit export sink (GW_0086); the secret contract is {@link DeclaredWebhook}'s.
     *
     * @param after ledger sequence the sink starts after — applied at creation only; the cursor is
     *     runtime progress and is never touched by a later reconciliation
     * @param batchSize maximum ledger entries per batch; null uses the audit-export default
     */
    public record DeclaredAuditSink(String name, String url, String secret, Long after, Integer batchSize) {}

    /**
     * A declared CEL policy deny rule (GW_0089), reconciled through the same compiled, audited
     * path as the policy API: an expression that does not compile to a boolean is an isolated
     * entry failure, never a stored rule.
     *
     * @param enabled whether the rule gates approvals; null means enabled — a declared rule is
     *     declared to enforce
     */
    public record DeclaredPolicyRule(String name, String description, String expression, Boolean enabled) {}

    /**
     * Delegated administration (GW_0068, GW_0071). {@code enabled=false} — the default — makes
     * every authorization check pass, so an upgrade never locks anyone out; a deployment stages
     * its grants and then opts in. {@code admins} are admins by configuration and cannot be
     * revoked through the API — the escape hatch that survives a bad grant edit.
     */
    public record Roles(Boolean enabled, List<String> admins, String claim, List<ClaimMapping> mappings) {

        /** The claim an enterprise directory most often carries group membership in. */
        public static final String DEFAULT_CLAIM = "groups";

        public Roles {
            if (enabled == null) {
                enabled = false;
            }
            admins = admins == null ? List.of() : List.copyOf(admins);
            if (claim == null || claim.isBlank()) {
                claim = DEFAULT_CLAIM;
            }
            mappings = mappings == null ? List.of() : List.copyOf(mappings);
        }
    }

    /**
     * One identity-provider claim value granting one role (GW_0098). The value is the provider's
     * own — a group object id, an app-role value — so it is matched exactly and never by
     * convention; an {@code approver} mapping names the marketplace it is scoped to and the global
     * roles name none, which {@code ClaimRoleMapper} refuses to start without.
     *
     * <p>The named marketplace need not exist yet: registration may come later, including from
     * {@link Estate}, and until then the mapping simply matches nothing.
     */
    public record ClaimMapping(String claimValue, String role, String marketplace) {}

    /**
     * Browser-login integrity beyond what the client registration expresses (GW_0100).
     *
     * @param issuer the ID-token issuer to require. Null — the default, for compatibility — runs
     *     Spring Security's own checks only, which compare no issuer at all when the registration
     *     carries none; the gateway warns at startup while that is the case. Where one
     *     authorization endpoint serves many tenants, this is the tenant boundary.
     */
    public record Oidc(String issuer) {}

    /**
     * Access-token policy (GW_0065).
     *
     * @param maxTtl the longest lifetime creation accepts; a request beyond it is refused, never
     *     silently clamped. Null — the default, for compatibility — accepts tokens with no expiry.
     */
    public record Tokens(Duration maxTtl) {}

    /**
     * The global virtual catalog (GW_0061–GW_0063). {@code name} is reserved: registration
     * refuses it, because the catalog occupies that facade path.
     *
     * @param enabled whether publications and revocations rebuild the catalog and the endpoints
     *     answer; an existing catalog repository is never deleted by turning this off
     * @param name the catalog's facade path segment and reserved marketplace name
     */
    public record Catalog(Boolean enabled, String name) {

        public Catalog {
            if (enabled == null) {
                enabled = true;
            }
            if (name == null || name.isBlank()) {
                name = "catalog";
            }
        }
    }

    /**
     * Upstream sync (GW_0056–GW_0059). {@code enabled=true} is safe on upgrade: the sweep only
     * touches marketplaces an operator has explicitly moved to {@code scheduled}, so a default
     * estate (all {@code on-demand}) sees no behavior change.
     *
     * @param enabled whether the scheduled polling sweep runs; the inbound webhook endpoint and
     *     the mode endpoint work either way
     * @param pollInterval how often the sweep runs
     * @param batchSize how many scheduled marketplaces one sweep pass ingests, least recently
     *     attempted first
     * @param maxWebhookBodyBytes inbound webhook bodies larger than this are rejected before the
     *     HMAC is computed, bounding the work an unauthenticated caller can cause
     */
    public record Sync(Boolean enabled, Duration pollInterval, Integer batchSize, Long maxWebhookBodyBytes) {

        public Sync {
            if (enabled == null) {
                enabled = true;
            }
            if (pollInterval == null) {
                pollInterval = Duration.ofMinutes(10);
            }
            if (batchSize == null || batchSize <= 0) {
                batchSize = 10;
            }
            if (maxWebhookBodyBytes == null || maxWebhookBodyBytes <= 0) {
                maxWebhookBodyBytes = 1024L * 1024L;
            }
        }
    }

    /**
     * The vetting chain (GW_0037-GW_0043). There is deliberately no enable/disable switch: the
     * chain is the approval gate's evidence, and an operator who could switch it off would be
     * switching off the record rather than the gate — a snapshot with no chain run is blocked
     * either way, so the only thing a kill switch would buy is a blocked estate with no findings.
     *
     * @param timeout how long a single connector may take before its verdict is recorded as an
     *     error, which blocks; a wedged connector must never wedge ingestion
     * @param maxFileBytes files larger than this are handed to connectors as unread, and reported
     *     as an informational finding rather than skipped in silence
     * @param waiverSweepInterval how often lapsed waivers are noted in the ledger (GW_0048). This
     *     knob cannot open a hole: a waiver stops suppressing its finding the moment the effective
     *     outcome is next computed, whether or not the sweep has run, so the interval only decides
     *     how promptly the lapse is announced.
     * @param waiverSweepBatchSize how many lapsed waivers one sweep pass records
     * @param minimumReleaseAge the cooling-off window a snapshot must clear before it can be
     *     approved (GW_0073), measured from the instant the gateway first ingested its commit.
     *     Zero — the default — disables the gate entirely, so an upgrade changes nothing. Like
     *     waiver expiry this is a comparison made per approval request, not a scheduled state, so
     *     the wait clears itself and no sweep can be late.
     * @param revet continuous re-vetting of approved content (GW_0049-GW_0054)
     * @param license the org-level license policy (GW_0094)
     */
    public record Vetting(
            Duration timeout,
            Long maxFileBytes,
            Duration waiverSweepInterval,
            Integer waiverSweepBatchSize,
            Duration minimumReleaseAge,
            Revet revet,
            License license) {

        public Vetting {
            if (minimumReleaseAge == null || minimumReleaseAge.isNegative()) {
                minimumReleaseAge = Duration.ZERO;
            }
            if (timeout == null) {
                timeout = Duration.ofSeconds(30);
            }
            if (maxFileBytes == null || maxFileBytes <= 0) {
                maxFileBytes = 1024L * 1024L;
            }
            if (waiverSweepInterval == null) {
                waiverSweepInterval = Duration.ofHours(1);
            }
            if (waiverSweepBatchSize == null || waiverSweepBatchSize <= 0) {
                waiverSweepBatchSize = 200;
            }
            if (revet == null) {
                revet = new Revet(null, null, null, null, null);
            }
            if (license == null) {
                license = new License(null, null);
            }
        }
    }

    /**
     * The organisation-level license policy (GW_0094), evaluated by the built-in license-scan
     * vetting connector and reported by the per-snapshot license endpoint (GW_0095).
     *
     * <p>Deliberately configuration rather than API-managed runtime state: vetting policy must be
     * attributable per chain run (GW_0049), and a policy that changes only by deploy — its digest
     * stamped into the connector's recorded version — keeps every run's chain identity naming the
     * policy it ran under. Both lists default to empty, under which identified licenses are
     * informational and unknown or missing licenses only warn, so an upgrade blocks nothing.
     *
     * @param allowed SPDX ids; when non-empty, any license not on it — and any unknown or missing
     *     license — is a blocking finding
     * @param banned SPDX ids whose detection is a blocking finding; checked before the allow list
     */
    public record License(List<String> allowed, List<String> banned) {

        public License {
            allowed = allowed == null ? List.of() : List.copyOf(allowed);
            banned = banned == null ? List.of() : List.copyOf(banned);
        }

        public boolean allowListConfigured() {
            return !allowed.isEmpty();
        }
    }

    /**
     * Continuous re-vetting of already-approved content (GW_0049-GW_0051).
     *
     * <p>The two switches answer different questions and default differently on purpose.
     * {@code enabled} controls whether fresh <em>evidence</em> is produced, and defaults to true:
     * re-running read-only scanners over pinned content writes a run and changes nothing else, and
     * an estate whose approvals are never re-examined is exactly the gap this feature closes.
     * {@code mode} controls whether that evidence <em>retracts</em> content, and defaults to
     * {@code WARN}: auto-quarantine pulls skills out from under every team that fetched them, so an
     * upgrade must never start doing it. An operator turns on enforcement once they have watched
     * warn mode for a cycle and know the blast radius.
     *
     * @param enabled whether the scheduled sweep runs; the manual endpoints work either way, so a
     *     re-vet can always be asked for on demand
     * @param interval how often the sweep runs
     * @param cadence how long a snapshot's latest run may be before the sweep picks it again.
     *     Together with {@code batchSize} this is what stops a tick from re-vetting everything: the
     *     sweep takes the oldest-vetted snapshots first, so a large estate is covered over many
     *     ticks rather than all at once.
     * @param batchSize how many snapshots one sweep pass re-vets
     * @param mode what a violation does; see {@link RevetMode}
     */
    public record Revet(Boolean enabled, Duration interval, Duration cadence, Integer batchSize, RevetMode mode) {

        public Revet {
            if (enabled == null) {
                enabled = true;
            }
            if (interval == null) {
                interval = Duration.ofHours(6);
            }
            if (cadence == null) {
                cadence = Duration.ofHours(24);
            }
            if (batchSize == null || batchSize <= 0) {
                batchSize = 25;
            }
            if (mode == null) {
                mode = RevetMode.WARN;
            }
        }

        public boolean enforcing() {
            return mode == RevetMode.ENFORCE;
        }
    }

    /** What a re-vetting violation does to the snapshot it was found on (GW_0050, GW_0051). */
    public enum RevetMode {

        /**
         * Record and announce, change nothing. The violation lands in the ledger, the lifecycle
         * event goes out, and the portal shows it — but the snapshot stays approved and published.
         * The default, and the way to measure a policy before it can take content away.
         */
        WARN,

        /** Revoke the snapshot and stop serving it (GW_0050). */
        ENFORCE
    }

    /**
     * Snapshot retention (GW_0031–GW_0034). {@code enabled=false} — the default — stops both
     * scheduled passes: an upgrade never deletes anything until an operator opts in, while the
     * on-demand endpoints stay available for a dry run.
     */
    public record Retention(
            Boolean enabled,
            Duration pollInterval,
            Duration compactionInterval,
            Integer batchSize,
            Policy defaults,
            Map<String, Policy> marketplaces) {

        private static final Policy FALLBACK =
                new Policy(Duration.ofDays(90), true, Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(14));

        public Retention {
            if (enabled == null) {
                enabled = false;
            }
            if (pollInterval == null) {
                pollInterval = Duration.ofHours(1);
            }
            if (compactionInterval == null) {
                compactionInterval = Duration.ofHours(6);
            }
            if (batchSize == null) {
                batchSize = 200;
            }
            defaults = merge(defaults, FALLBACK);
            marketplaces = marketplaces == null ? Map.of() : Map.copyOf(marketplaces);
        }

        /** The policy in force for a marketplace: its overrides over the global defaults. */
        public Policy policyFor(String marketplace) {
            return merge(marketplaces.get(marketplace), defaults);
        }

        private static Policy merge(Policy override, Policy base) {
            if (override == null) {
                return base;
            }
            return new Policy(
                    override.heldMaxAge() == null ? base.heldMaxAge() : override.heldMaxAge(),
                    override.superseded() == null ? base.superseded() : override.superseded(),
                    override.supersededMinAge() == null ? base.supersededMinAge() : override.supersededMinAge(),
                    override.minIdle() == null ? base.minIdle() : override.minIdle(),
                    override.restoreWindow() == null ? base.restoreWindow() : override.restoreWindow());
        }

        /**
         * One resolved retention policy. Fields are nullable only so a per-marketplace override can
         * leave a knob unset and inherit it; {@link #policyFor(String)} always returns a complete one.
         *
         * @param heldMaxAge how long a snapshot may stay held before it is eligible; zero or
         *     negative disables the criterion
         * @param superseded whether a non-approved snapshot overtaken by a later approved snapshot
         *     of the same marketplace is eligible
         * @param supersededMinAge minimum age a superseded snapshot must reach to be eligible
         * @param minIdle a snapshot fetched through the facade within this window is never eligible
         * @param restoreWindow how long a soft-deleted snapshot stays restorable before compaction
         *     may remove it permanently
         */
        public record Policy(
                Duration heldMaxAge,
                Boolean superseded,
                Duration supersededMinAge,
                Duration minIdle,
                Duration restoreWindow) {

            public boolean heldCriterionEnabled() {
                return heldMaxAge != null && !heldMaxAge.isZero() && !heldMaxAge.isNegative();
            }

            public boolean supersededCriterionEnabled() {
                return Boolean.TRUE.equals(superseded);
            }
        }
    }

    /** Audit ledger export (GW_0027–GW_0029); {@code enabled=false} stops the exporter poller only. */
    public record AuditExport(
            Boolean enabled,
            Duration pollInterval,
            Duration lag,
            Integer batchSize,
            Integer defaultPageSize,
            Integer maxPageSize) {

        public AuditExport {
            if (enabled == null) {
                enabled = true;
            }
            if (pollInterval == null) {
                pollInterval = Duration.ofSeconds(30);
            }
            // Commit-settling window: a BIGSERIAL id is assigned before commit, so an entry with a
            // lower id can become visible after a higher one. Ignoring entries younger than this
            // closes the window a cursor would otherwise skip over.
            if (lag == null) {
                lag = Duration.ofSeconds(5);
            }
            if (batchSize == null) {
                batchSize = 500;
            }
            if (defaultPageSize == null) {
                defaultPageSize = 1000;
            }
            if (maxPageSize == null) {
                maxPageSize = 10000;
            }
        }
    }

    /** Outbound lifecycle webhook dispatch (GW_0025); {@code enabled=false} stops the poller only. */
    public record Webhooks(
            Boolean enabled,
            Duration pollInterval,
            Duration baseBackoff,
            Duration maxBackoff,
            Integer maxAttempts,
            Duration timeout,
            Integer batchSize) {

        public Webhooks {
            if (enabled == null) {
                enabled = true;
            }
            if (pollInterval == null) {
                pollInterval = Duration.ofSeconds(5);
            }
            if (baseBackoff == null) {
                baseBackoff = Duration.ofSeconds(10);
            }
            if (maxBackoff == null) {
                maxBackoff = Duration.ofHours(1);
            }
            if (maxAttempts == null) {
                maxAttempts = 5;
            }
            if (timeout == null) {
                timeout = Duration.ofSeconds(10);
            }
            if (batchSize == null) {
                batchSize = 50;
            }
        }
    }
}

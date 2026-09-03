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
        Approval approval,
        Sync sync,
        Catalog catalog,
        Tokens tokens,
        Roles roles,
        Oidc oidc,
        Estate estate,
        Storage storage,
        Ingestion ingestion) {

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
        if (approval == null) {
            approval = new Approval(null);
        }
        if (sync == null) {
            sync = new Sync(null, null, null, null);
        }
        if (catalog == null) {
            catalog = new Catalog(null, null);
        }
        if (tokens == null) {
            tokens = new Tokens(null, null);
        }
        if (roles == null) {
            roles = new Roles(null, null, null);
        }
        if (oidc == null) {
            oidc = new Oidc(null);
        }
        if (estate == null) {
            estate = new Estate(null, null, null, null, null);
        }
        if (storage == null) {
            storage = new Storage(null, null, null);
        }
        if (ingestion == null) {
            ingestion = new Ingestion(null);
        }
    }

    /**
     * Ingestion-time policy (GW_0151). Its one block today is external plugin sources; it exists as
     * a block of its own so the resolution and hardening knobs that follow have somewhere to land
     * that is not the top level.
     */
    public record Ingestion(ExternalSources externalSources) {

        public Ingestion {
            if (externalSources == null) {
                externalSources = new ExternalSources(null, null, null, null);
            }
        }
    }

    /**
     * Admission of plugin sources that live outside the marketplace repository (GW_0151). Every
     * default here is the behaviour that shipped before this block existed, so an absent block —
     * which is every existing deployment — rejects external sources exactly as GW_0003 always did.
     *
     * <p>An enabled gateway does not yet <em>resolve</em> what it admits: an admitted source is
     * still recorded as a rejected snapshot under GW_0152, with a violation that says so, until the
     * resolver lands. Enabling this is therefore a deliberate step towards that, not a way to serve
     * external content today.
     *
     * @param enabled whether any external source may be admitted at all; false is GW_0003's
     *     local-only behaviour
     * @param allowedTypes the source types an enabled gateway will consider. Only types something
     *     can resolve belong here, so the allowlist never advertises a form nothing implements
     * @param allowedHosts exact hosts the derived clone URL may name; empty means any host. Never a
     *     suffix or pattern match — an entry of github.com must not admit evil-github.com
     * @param maxSources how many external sources one manifest may declare, bounding the work a
     *     hostile manifest can cause once each source becomes a fetch
     */
    public record ExternalSources(
            Boolean enabled, List<String> allowedTypes, List<String> allowedHosts, Integer maxSources) {

        public ExternalSources {
            if (enabled == null) {
                enabled = false;
            }
            if (allowedTypes == null) {
                allowedTypes = List.of("github");
            }
            if (allowedHosts == null) {
                allowedHosts = List.of();
            }
            if (maxSources == null || maxSources <= 0) {
                maxSources = 20;
            }
        }
    }

    /**
     * Which git storage backend holds the repositories, and how to reach it (GW_0111).
     *
     * <p>The backend is <em>named</em>, never inferred. An absent block is {@code filesystem},
     * which is what every existing deployment already has, so an upgrade changes nothing; an
     * unrecognised name fails startup through Spring's own enum binding; and an
     * {@code object-store} selection that cannot be completed fails startup rather than falling
     * back to a filesystem nobody asked for. A gateway serving from storage the operator did not
     * choose is the same class of defect as a volume that silently loses published content.
     *
     * @param backend the named backend; null is {@link Backend#FILESYSTEM}
     * @param objectStore how to reach the bucket; required, and validated, only when the backend
     *     is {@link Backend#OBJECT_STORE}
     * @param migration the one-shot offline copy between backends (GW_0114); off unless asked for
     */
    public record Storage(Backend backend, ObjectStore objectStore, Migration migration) {

        public Storage {
            if (backend == null) {
                backend = Backend.FILESYSTEM;
            }
            if (objectStore == null) {
                objectStore = new ObjectStore(null, null, null, null, null, null, null, null);
            }
            if (migration == null) {
                migration = new Migration(null, null);
            }
        }

        /**
         * The one-shot offline copy from the configured backend into another one (GW_0114).
         *
         * <p>Both ends come from this same configuration: the source is whatever
         * {@code storage.backend} names, and {@code to} names the destination, so a migration and
         * the rollback that follows it are the same file with two values swapped. It is off unless
         * it is asked for, it runs before anything is served, and the process exits when it is
         * done — a migration is not a mode the gateway runs in.
         *
         * @param enabled whether this start is a migration rather than a service; null is false
         * @param to the destination backend; required when enabled, and it must not be the source
         */
        public record Migration(Boolean enabled, Backend to) {

            public Migration {
                if (enabled == null) {
                    enabled = false;
                }
            }
        }

        /** The backends a deployment may name. There is deliberately no {@code auto}. */
        public enum Backend {
            /** Bare repositories under {@code skills-gateway.data-dir}. The default. */
            FILESYSTEM,
            /** JGit DFS over an S3-compatible bucket, ref state in a conditionally written manifest. */
            OBJECT_STORE
        }
    }

    /**
     * An S3-compatible bucket and how to reach it.
     *
     * @param endpoint override for an S3-compatible store or an S3 VPC endpoint; null uses the
     *     SDK's regional endpoint
     * @param region the region to sign for; required, because an unsigned-for region is a runtime
     *     failure in the middle of an approval rather than a startup one
     * @param bucket the bucket holding every repository; required
     * @param prefix key prefix inside the bucket, so one bucket can hold more than one gateway
     * @param credentials how credentials are resolved; the mode is named for the same reason the
     *     backend is
     * @param cache local pack-cache and block-cache sizing, and the freshness bound that decides
     *     how long a revoked snapshot may still be advertised by a replica
     * @param connectionMaxIdleTime how long a pooled connection may sit unused before the client
     *     discards it. This must stay <em>below</em> the store's own idle timeout: a connection the
     *     store has already closed reads, on the first request after a quiet period, as a storage
     *     fault rather than as the pooling artefact it is. Observed for real while probing a store
     *     during this change's spike, which is why it is a setting and not a default nobody named
     * @param connectionTimeToLive an upper bound on a pooled connection's total life, so a
     *     connection also stops being reused across a load balancer's own recycling
     */
    public record ObjectStore(
            String endpoint,
            String region,
            String bucket,
            String prefix,
            Credentials credentials,
            Cache cache,
            Duration connectionMaxIdleTime,
            Duration connectionTimeToLive) {

        public ObjectStore {
            if (prefix == null) {
                prefix = "";
            }
            if (credentials == null) {
                credentials = new Credentials(null, null, null, null, null);
            }
            if (cache == null) {
                cache = new Cache(null, null, null, null, null, null);
            }
            if (connectionMaxIdleTime == null || connectionMaxIdleTime.isNegative()) {
                connectionMaxIdleTime = Duration.ofSeconds(20);
            }
            if (connectionTimeToLive == null || connectionTimeToLive.isNegative()) {
                connectionTimeToLive = Duration.ofMinutes(1);
            }
        }
    }

    /**
     * How the S3 client obtains credentials.
     *
     * <p>{@link Mode#WEB_IDENTITY} is the primary mechanism rather than an option to add later:
     * the first deployment target has no instance metadata service, so anything that leans on the
     * instance-profile leg of the default provider chain does not run there. Naming the mode means
     * a misconfigured deployment fails at startup saying so, instead of walking down the chain to
     * a metadata endpoint that never answers and timing out mid-approval.
     *
     * @param mode {@code default}, {@code web-identity} or {@code static}; null is {@code default}
     * @param accessKeyId static mode only
     * @param secretAccessKey static mode only; never logged, never audited, never echoed by any API
     * @param roleArn web-identity mode; null falls back to the standard {@code AWS_ROLE_ARN}
     *     environment variable, which is what a service-account annotation projects
     * @param tokenFile web-identity mode; null falls back to
     *     {@code AWS_WEB_IDENTITY_TOKEN_FILE}
     */
    public record Credentials(Mode mode, String accessKeyId, String secretAccessKey, String roleArn, String tokenFile) {

        public Credentials {
            if (mode == null) {
                mode = Mode.DEFAULT;
            }
        }

        /** How credentials are resolved. */
        public enum Mode {
            /** The SDK's own provider chain. */
            DEFAULT,
            /** Web-identity federation: IRSA, Workload Identity. No secret is held by the gateway. */
            WEB_IDENTITY,
            /** An access key pair, for stores with no role mechanism. */
            STATIC
        }
    }

    /**
     * Local caching and freshness, all of it bounded.
     *
     * <p>Nothing in either cache is authoritative: packs are immutable and content-named, so a
     * cached pack is never stale and deleting the whole cache at any moment is always safe. Only
     * the manifest is re-read — and {@code refFreshness} is how long a replica may keep serving a
     * ref map it has already read, which on the revocation path is a trust-boundary property and
     * not a tuning knob. Zero, the default, means every reference advertisement is preceded by a
     * conditional {@code GET} of the manifest, so the bound is "the next advertisement".
     *
     * @param dir where cached packs live; null puts them under {@code data-dir/object-store-cache}
     * @param maxBytes cache budget; the least recently used cached packs are evicted past it
     * @param blockCacheBytes size of JGit's in-process DFS block cache
     * @param blockSizeBytes DFS block size
     * @param refFreshness how long a read ref map may be reused before the manifest is re-checked
     * @param packGrace how long a pack no manifest references any more is kept before deletion,
     *     so a replica part-way through streaming it does not get a 404 mid-fetch
     */
    public record Cache(
            Path dir,
            Long maxBytes,
            Long blockCacheBytes,
            Integer blockSizeBytes,
            Duration refFreshness,
            Duration packGrace) {

        public Cache {
            if (maxBytes == null || maxBytes <= 0) {
                maxBytes = 2L * 1024 * 1024 * 1024;
            }
            if (blockCacheBytes == null || blockCacheBytes <= 0) {
                blockCacheBytes = 128L * 1024 * 1024;
            }
            if (blockSizeBytes == null || blockSizeBytes <= 0) {
                blockSizeBytes = 64 * 1024;
            }
            if (refFreshness == null || refFreshness.isNegative()) {
                refFreshness = Duration.ZERO;
            }
            if (packGrace == null || packGrace.isNegative()) {
                packGrace = Duration.ofHours(1);
            }
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
    public record Roles(List<String> admins, String claim, List<ClaimMapping> mappings) {

        /** The claim an enterprise directory most often carries group membership in. */
        public static final String DEFAULT_CLAIM = "groups";

        public Roles {
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
     * @param sessionTtl what a session-derived credential is *granted* (GW_0104), as opposed to
     *     what a holder may ask for. Deliberately not derived from {@code maxTtl}: a deployment
     *     may allow year-long CI tokens and still want session credentials to die at lunchtime.
     */
    public record Tokens(Duration maxTtl, Duration sessionTtl) {

        /** About a working day: the credential lasts as long as the work does, and no longer. */
        public static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(8);

        /**
         * The cap a machine API credential is held to when {@code max-ttl} is unset (GW_0131).
         *
         * <p>Ninety days: a quarter, which is short enough that a forgotten credential in a
         * pipeline variable expires within one planning cycle rather than outliving the service
         * it was minted for, and long enough that rotating it is a scheduled chore rather than an
         * interruption. It exists because "mandatory expiry" alone admits {@code now + 100 years}
         * whenever no cap is configured, which is the never-expiring credential this rule was
         * written to prevent, spelled differently. An operator who wants longer sets
         * {@code skills-gateway.tokens.max-ttl} explicitly — and that is the point: a long-lived
         * control-plane credential should be a stated choice, not the consequence of leaving a
         * property blank. A configured cap, longer or shorter, always wins.
         */
        public static final Duration DEFAULT_MACHINE_MAX_TTL = Duration.ofDays(90);

        public Tokens {
            if (sessionTtl == null) {
                sessionTtl = DEFAULT_SESSION_TTL;
            }
        }

        /** The cap that applies to a machine credential: the configured one, or the built-in. */
        public Duration machineMaxTtl() {
            return maxTtl == null ? DEFAULT_MACHINE_MAX_TTL : maxTtl;
        }
    }

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
     * The approval gate's own settings (GW_0097). Only the separation-of-duties rule lives here so
     * far; the vetting, policy and cooling-off preconditions predate it and stay where they are.
     */
    public record Approval(FourEyes fourEyes) {

        public Approval {
            if (fourEyes == null) {
                fourEyes = new FourEyes(null);
            }
        }
    }

    /**
     * Separation of duties on approval (GW_0096, GW_0097): whether a reviewer who is also the
     * snapshot's ingestion actor, the marketplace's registrant, or the author of a waiver the
     * approval relies on may publish it.
     *
     * <p>There is deliberately no {@code enabled} flag. A control an operator can switch off
     * without leaving a trace is the gap this closes, so {@code warn} is the floor: every conflict
     * reaches the audit ledger whatever the mode, and the mode decides only whether the approval
     * is also refused.
     *
     * <p>The default is {@code warn}, and that is load-bearing rather than timid. A deployment
     * with one administrator — a first evaluation, a small team, a single-person estate — has
     * nobody to be the second pair of eyes, and an upgrade that silently made every approval
     * impossible would be a worse failure than the one being prevented. Enforcement is what an
     * organisation opts into once at least two principals hold approval rights in every
     * marketplace that needs deciding.
     *
     * @param mode what a detected conflict does; see {@link FourEyesMode}
     */
    public record FourEyes(FourEyesMode mode) {

        /** The property an operator sets, quoted in the refusal so the answer is discoverable. */
        public static final String CONFIG_KEY = "skills-gateway.approval.four-eyes.mode";

        public FourEyes {
            if (mode == null) {
                mode = FourEyesMode.WARN;
            }
        }

        public boolean enforcing() {
            return mode == FourEyesMode.ENFORCE;
        }
    }

    /** What a detected four-eyes conflict does to the approval that raised it (GW_0097). */
    public enum FourEyesMode {

        /**
         * Record and announce, approve anyway. The conflict lands on the audit ledger and the
         * portal says so before the reviewer confirms — but the snapshot is published. The
         * default, and what keeps a single-administrator deployment usable while still making
         * every self-approval visible after the fact.
         */
        WARN,

        /** Refuse the approval fail-closed; the snapshot stays held and nothing is published. */
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

package dev.skillsgateway.server.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

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
        Ingestion ingestion,
        Mirror mirror) {

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
            retention = new Retention(null, null, null, null, null, null, null);
        }
        if (vetting == null) {
            vetting = new Vetting(null, null, null, null, null, null, null, null, null);
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
        if (mirror == null) {
            mirror = new Mirror(null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * The optional read-only forge mirror (GW_FACADE_0020, GW_FACADE_0021). Off by default, and off is the whole
     * of an existing deployment's behaviour: nothing here is read until {@link #enabled} is true,
     * and the gateway contacts the mirror only when something it serves has changed or an
     * administrator asks for the drift report.
     *
     * <p>The mirror is a browsing convenience and never a serving surface (ADR 0008). Nothing about
     * approval, publication, revocation or what the facade serves may come to depend on it, which
     * is why there is no "wait for the push" or "fail the approval" knob here and why one cannot be
     * added without contradicting GW_FACADE_0021.
     *
     * @param enabled whether approved content is mirrored at all; false is the shipped behaviour
     * @param marketplace the single marketplace this increment mirrors; required when enabled
     * @param url the mirror's clone URL. Its scheme faces the same {@code allowed-url-schemes}
     *     allowlist that governs registration, and it may not carry userinfo — a credential in a
     *     URL ends up in every diagnostic that prints one, so it goes in {@link #username} and
     *     {@link #token} instead
     * @param username the forge account or token name the push authenticates as
     * @param token the push credential. Configuration only, never committed, never logged
     * @param timeout how long one mirror operation may take before it is abandoned as failed
     * @param maxAttempts how many times one mirror update is attempted before it is left as drift
     * @param retryDelay how long to wait between those attempts
     * @param sweepEnabled whether the recurring reconciliation runs (GW_FACADE_0025). True by default, and
     *     irrelevant while {@link #enabled} is false. It is on with the mirror because a mirror that
     *     tracks what is served only when an approval happens to occur — unless you also find and
     *     set a second flag — is a trap; the flag exists so that a suite seeding its own drift can
     *     keep a background actor from repairing it mid-assertion
     * @param sweepInterval how often that reconciliation runs, and therefore the bound on how long a
     *     reference the facade no longer serves can remain on the mirror
     * @param sweepInitialDelay how long after startup the first one runs. Short on purpose: a
     *     restart is exactly when the in-memory queue was lost, so the first sweep is what closes
     *     that hole
     */
    public record Mirror(
            Boolean enabled,
            String marketplace,
            String url,
            String username,
            String token,
            Duration timeout,
            Integer maxAttempts,
            Duration retryDelay,
            Boolean sweepEnabled,
            Duration sweepInterval,
            Duration sweepInitialDelay) {

        public Mirror {
            if (enabled == null) {
                enabled = false;
            }
            if (timeout == null) {
                timeout = Duration.ofSeconds(30);
            }
            if (maxAttempts == null || maxAttempts < 1) {
                maxAttempts = 3;
            }
            if (retryDelay == null) {
                retryDelay = Duration.ofSeconds(5);
            }
            if (sweepEnabled == null) {
                sweepEnabled = true;
            }
            if (sweepInterval == null) {
                sweepInterval = Duration.ofMinutes(15);
            }
            if (sweepInitialDelay == null) {
                sweepInitialDelay = Duration.ofMinutes(1);
            }
        }

        /** The credential is deliberately absent: a record's generated toString would print it. */
        @Override
        public String toString() {
            return "Mirror[enabled=%s, marketplace=%s, url=%s, username=%s, timeout=%s, maxAttempts=%d,"
                    + " retryDelay=%s, sweepEnabled=%s, sweepInterval=%s, sweepInitialDelay=%s]"
                            .formatted(
                                    enabled,
                                    marketplace,
                                    url,
                                    username,
                                    timeout,
                                    maxAttempts,
                                    retryDelay,
                                    sweepEnabled,
                                    sweepInterval,
                                    sweepInitialDelay);
        }
    }

    /**
     * Ingestion-time policy (GW_INGEST_0020). Its one block today is external plugin sources; it exists as
     * a block of its own so the resolution and hardening knobs that follow have somewhere to land
     * that is not the top level.
     */
    public record Ingestion(ExternalSources externalSources) {

        public Ingestion {
            if (externalSources == null) {
                externalSources = new ExternalSources(null, null, null, null, null, null, null);
            }
        }
    }

    /**
     * Admission of plugin sources that live outside the marketplace repository (GW_INGEST_0020). Every
     * default here is the behaviour that shipped before this block existed, so an absent block —
     * which is every existing deployment — rejects external sources exactly as GW_INGEST_0003 always did.
     *
     * <p>An enabled gateway resolves what it admits (GW_INGEST_0023, GW_INGEST_0024.1): the source is fetched into
     * quarantine and grafted into a composite snapshot whose manifest is entirely gateway-local.
     * Enabling this therefore opens the gateway's only manifest-driven outbound network path, which
     * is what {@link #allowPrivateNetworks} and {@link #budgets} bound — and why the primary control
     * remains network egress isolation rather than anything in this block (ADR 0011).
     *
     * @param enabled whether any external source may be admitted at all; false is GW_INGEST_0003's
     *     local-only behaviour
     * @param allowedTypes the source types an enabled gateway will consider. Only types something
     *     can resolve belong here, so the allowlist never advertises a form nothing implements
     * @param allowedHosts exact hosts the derived clone URL may name; empty means any host. Never a
     *     suffix or pattern match — an entry of github.com must not admit evil-github.com
     * @param maxSources how many external sources one manifest may declare, bounding the work a
     *     hostile manifest can cause once each source becomes a fetch
     * @param githubBaseUrl where a {@code github} shorthand's {@code owner/repo} is resolved
     *     against, for GitHub Enterprise Server. The derived URL still faces the scheme and host
     *     allowlists, so this cannot widen what a manifest may reach beyond what an operator
     *     allowed; and because the shorthand cannot contain a host, this is the only place the
     *     host of a github source is ever decided
     * @param allowPrivateNetworks whether a source may resolve to a loopback, RFC1918,
     *     carrier-grade-NAT or unique-local address. False is the default. It never permits a
     *     link-local address — the cloud metadata endpoint is link-local, and a development
     *     topology that needs loopback must not unlock it as a side effect (GW_INGEST_0025)
     * @param budgets what one manifest may cost to resolve (GW_INGEST_0026)
     */
    public record ExternalSources(
            Boolean enabled,
            List<String> allowedTypes,
            List<String> allowedHosts,
            Integer maxSources,
            String githubBaseUrl,
            Boolean allowPrivateNetworks,
            ResolutionBudgets budgets) {

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
            if (githubBaseUrl == null || githubBaseUrl.isBlank()) {
                githubBaseUrl = "https://github.com";
            }
            while (githubBaseUrl.endsWith("/")) {
                githubBaseUrl = githubBaseUrl.substring(0, githubBaseUrl.length() - 1);
            }
            if (allowPrivateNetworks == null) {
                allowPrivateNetworks = false;
            }
            if (budgets == null) {
                budgets = new ResolutionBudgets(null, null, null, null, null, null, null, null, null);
            }
            if (maxSources == null || maxSources <= 0) {
                maxSources = 20;
            }
        }
    }

    /**
     * What resolving one manifest's external plugin sources may cost (GW_INGEST_0026).
     *
     * <p>A git fetch is decompression of a stream the gateway did not create, so an unbounded
     * resolver turns any manifest the gateway will look at into a denial-of-service primitive
     * against the gateway itself. Two byte bounds rather than one: the received bound stops a
     * stream that never ends, and the inflated bound and the ratio stop a stream that ends quickly
     * and expands enormously. Neither catches the other's case.
     *
     * <p>One setting per sub-requirement of GW_INGEST_0026, so a verdict on this budget says which bound
     * moved rather than only that some bound did.
     *
     * @param maxReceivedBytes bytes one source may send on the wire before the transfer is aborted
     *     (GW_INGEST_0026.1)
     * @param maxInflatedBytes total size of one source's content once it is objects on disk
     *     (GW_INGEST_0026.2)
     * @param maxClosureBytes the same, accumulated over every source one manifest declares
     *     (GW_INGEST_0026.3)
     * @param maxInflationRatio inflated bytes per received byte, which is what a pack bomb maximises
     *     (GW_INGEST_0026.4)
     * @param maxObjects blobs and trees one source may contribute (GW_INGEST_0026.5)
     * @param maxBlobBytes the largest single file one source may contribute (GW_INGEST_0026.6)
     * @param maxTreeDepth how deep a directory tree the gateway will accept, bounding every later
     *     walk of the content as well as the fetch (GW_INGEST_0026.7)
     * @param maxRedirects redirect hops one request may take (GW_INGEST_0025)
     * @param deadline wall-clock budget for resolving a whole manifest, so a resolution that is
     *     slow rather than large still terminates (GW_INGEST_0026.8)
     */
    public record ResolutionBudgets(
            DataSize maxReceivedBytes,
            DataSize maxInflatedBytes,
            DataSize maxClosureBytes,
            Integer maxInflationRatio,
            Integer maxObjects,
            DataSize maxBlobBytes,
            Integer maxTreeDepth,
            Integer maxRedirects,
            Duration deadline) {

        public ResolutionBudgets {
            if (maxReceivedBytes == null || maxReceivedBytes.toBytes() <= 0) {
                maxReceivedBytes = DataSize.ofMegabytes(50);
            }
            if (maxInflatedBytes == null || maxInflatedBytes.toBytes() <= 0) {
                maxInflatedBytes = DataSize.ofMegabytes(200);
            }
            if (maxClosureBytes == null || maxClosureBytes.toBytes() <= 0) {
                maxClosureBytes = DataSize.ofMegabytes(500);
            }
            if (maxInflationRatio == null || maxInflationRatio <= 0) {
                maxInflationRatio = 100;
            }
            if (maxObjects == null || maxObjects <= 0) {
                maxObjects = 20000;
            }
            if (maxBlobBytes == null || maxBlobBytes.toBytes() <= 0) {
                maxBlobBytes = DataSize.ofMegabytes(10);
            }
            if (maxTreeDepth == null || maxTreeDepth <= 0) {
                maxTreeDepth = 32;
            }
            if (maxRedirects == null || maxRedirects < 0) {
                maxRedirects = 3;
            }
            if (deadline == null || deadline.isNegative() || deadline.isZero()) {
                deadline = Duration.ofMinutes(5);
            }
        }
    }

    /**
     * Which git storage backend holds the repositories, and how to reach it (GW_FACADE_0010).
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
     * @param migration the one-shot offline copy between backends (GW_FACADE_0013); off unless asked for
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
         * The one-shot offline copy from the configured backend into another one (GW_FACADE_0013).
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
     * The declarative estate (GW_ESTATE_0001–GW_ESTATE_0005): marketplaces, role grants, webhook subscribers and
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
     * A declared marketplace (GW_ESTATE_0002). There is deliberately no ref field: the ingested ref is the
     * gateway's decision (GW_INGEST_0006), so the declaration cannot express one.
     *
     * @param name gateway-local marketplace name, same rules as the API
     * @param url upstream clone URL; its scheme must be on the allowlist, and once registered it is
     *     immutable — a differing declared URL is a reconciliation failure, never an update
     * @param syncMode {@code on-demand} or {@code scheduled}; {@code webhook} is refused (its inbound
     *     HMAC secret is gateway-generated show-once, which has no declarative form). Null means the
     *     stored mode is not managed and never touched. A hosted marketplace accepts only
     *     {@code on-demand}: its ingestion trigger is the push.
     * @param origin {@code upstream} (the default) or {@code hosted} (GW_FACADE_0006); a hosted marketplace
     *     declares no url, and like a url the origin is immutable after registration
     * @param pushPolicy for a hosted marketplace, {@code append-only} (the default) or
     *     {@code allow-rewrite}
     */
    public record DeclaredMarketplace(String name, String url, String syncMode, String origin, String pushPolicy) {}

    /**
     * A declared role grant (GW_ESTATE_0003), the exact shape of the grants API: approver grants name one
     * marketplace that must exist at reconcile time (declared here or API-registered); admin and
     * auditor grants must not name one.
     */
    public record DeclaredGrant(String principal, String role, String marketplace) {}

    /**
     * A declared webhook subscriber (GW_ESTATE_0004). The signing secret is operator-supplied — reference
     * an environment variable ({@code ${...}}) rather than inlining a literal — and write-only:
     * never logged, never audited, never answered by any API. Changing the referenced value rotates
     * the stored secret idempotently.
     *
     * @param events comma-delimited event filter, or null/blank for every event
     */
    public record DeclaredWebhook(String name, String url, String events, String secret) {}

    /**
     * A declared audit export sink (GW_ESTATE_0004); the secret contract is {@link DeclaredWebhook}'s.
     *
     * @param after ledger sequence the sink starts after — applied at creation only; the cursor is
     *     runtime progress and is never touched by a later reconciliation
     * @param batchSize maximum ledger entries per batch; null uses the audit-export default
     */
    public record DeclaredAuditSink(String name, String url, String secret, Long after, Integer batchSize) {}

    /**
     * A declared CEL policy deny rule (GW_APPROVAL_0006), reconciled through the same compiled, audited
     * path as the policy API: an expression that does not compile to a boolean is an isolated
     * entry failure, never a stored rule.
     *
     * @param enabled whether the rule gates approvals; null means enabled — a declared rule is
     *     declared to enforce
     */
    public record DeclaredPolicyRule(String name, String description, String expression, Boolean enabled) {}

    /**
     * Delegated administration (GW_AUTH_0010, GW_AUTH_0013). {@code enabled=false} — the default — makes
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
     * One identity-provider claim value granting one role (GW_AUTH_0015). The value is the provider's
     * own — a group object id, an app-role value — so it is matched exactly and never by
     * convention; an {@code approver} mapping names the marketplace it is scoped to and the global
     * roles name none, which {@code ClaimRoleMapper} refuses to start without.
     *
     * <p>The named marketplace need not exist yet: registration may come later, including from
     * {@link Estate}, and until then the mapping simply matches nothing.
     */
    public record ClaimMapping(String claimValue, String role, String marketplace) {}

    /**
     * Browser-login integrity beyond what the client registration expresses (GW_AUTH_0017).
     *
     * @param issuer the ID-token issuer to require. Null — the default, for compatibility — runs
     *     Spring Security's own checks only, which compare no issuer at all when the registration
     *     carries none; the gateway warns at startup while that is the case. Where one
     *     authorization endpoint serves many tenants, this is the tenant boundary.
     */
    public record Oidc(String issuer) {}

    /**
     * Access-token policy (GW_AUTH_0007).
     *
     * @param maxTtl the longest lifetime creation accepts; a request beyond it is refused, never
     *     silently clamped. Null — the default, for compatibility — accepts tokens with no expiry.
     * @param sessionTtl what a session-derived credential is *granted* (GW_AUTH_0018), as opposed to
     *     what a holder may ask for. Deliberately not derived from {@code maxTtl}: a deployment
     *     may allow year-long CI tokens and still want session credentials to die at lunchtime.
     */
    public record Tokens(Duration maxTtl, Duration sessionTtl) {

        /** About a working day: the credential lasts as long as the work does, and no longer. */
        public static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(8);

        /**
         * The cap a machine API credential is held to when {@code max-ttl} is unset (GW_AUTH_0024).
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
     * The global virtual catalog (GW_FACADE_0003–GW_FACADE_0005). {@code name} is reserved: registration
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
     * Upstream sync (GW_INGEST_0010–GW_INGEST_0013). {@code enabled=true} is safe on upgrade: the sweep only
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
     * The vetting chain (GW_VETTING_0001-GW_VETTING_0006). There is deliberately no enable/disable switch: the
     * chain is the approval gate's evidence, and an operator who could switch it off would be
     * switching off the record rather than the gate — a snapshot with no chain run is blocked
     * either way, so the only thing a kill switch would buy is a blocked estate with no findings.
     *
     * @param timeout how long a single connector may take before its verdict is recorded as an
     *     error, which blocks; a wedged connector must never wedge ingestion
     * @param maxFileBytes files larger than this are handed to connectors as unread, and reported
     *     as an informational finding rather than skipped in silence
     * @param contentCacheBytes how much of a snapshot's content one chain run may hold so that the
     *     connectors after the first read it instead of inflating it again (GW_VETTING_0030). Past it
     *     content is re-read rather than kept, so this is a speed setting and never a coverage
     *     one. It is bounded rather than unlimited because the content is an upstream repository
     *     the gateway does not control.
     * @param waiverSweepInterval how often lapsed waivers are noted in the ledger (GW_VETTING_0011). This
     *     knob cannot open a hole: a waiver stops suppressing its finding the moment the effective
     *     outcome is next computed, whether or not the sweep has run, so the interval only decides
     *     how promptly the lapse is announced.
     * @param waiverSweepBatchSize how many lapsed waivers one sweep pass records
     * @param minimumReleaseAge the cooling-off window a snapshot must clear before it can be
     *     approved (GW_APPROVAL_0004.1), measured from the instant the gateway first ingested its
     *     commit. Zero — the default — disables the gate entirely, so an upgrade changes nothing
     *     (GW_APPROVAL_0004.2). Like waiver expiry this is a comparison made per approval request,
     *     not a scheduled state, so the wait clears itself and no sweep can be late
     *     (GW_APPROVAL_0004.2).
     * @param revet continuous re-vetting of approved content (GW_VETTING_0012-GW_VETTING_0017)
     * @param license the org-level license policy (GW_VETTING_0020)
     * @param conformance the posture of the built-in SKILL.md conformance connector (GW_INGEST_0028)
     */
    public record Vetting(
            Duration timeout,
            Long maxFileBytes,
            Long contentCacheBytes,
            Duration waiverSweepInterval,
            Integer waiverSweepBatchSize,
            Duration minimumReleaseAge,
            Revet revet,
            License license,
            Conformance conformance) {

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
            if (contentCacheBytes == null || contentCacheBytes <= 0) {
                contentCacheBytes = 32L * 1024L * 1024L;
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
            if (conformance == null) {
                conformance = new Conformance(null);
            }
        }
    }

    /**
     * The posture of the built-in {@code skill-conformance} connector (GW_INGEST_0028).
     *
     * <p>Defaults to advisory, and the default is the load-bearing part. A verdict covers a whole
     * snapshot, so a blocking default would let one malformed skill hold up every other skill in
     * the marketplace beside it — and a formatting defect is not what the gateway's blocking
     * states are for. An operator who has decided conformance is a publishing requirement turns
     * this on after watching the advisory findings for a cycle, which is the same on-ramp the
     * license lists and re-vetting enforcement offer.
     *
     * <p>Like the license lists this is configuration rather than API-managed runtime state: it is
     * stamped into the connector's recorded version, so every run names the posture it ran under
     * and a changed answer about unchanged content stays attributable (GW_VETTING_0012).
     *
     * @param enforce whether conformance defects block approval instead of warning
     */
    public record Conformance(Boolean enforce) {

        public Conformance {
            if (enforce == null) {
                enforce = false;
            }
        }
    }

    /**
     * The organisation-level license policy (GW_VETTING_0020), evaluated by the built-in license-scan
     * vetting connector and reported by the per-snapshot license endpoint (GW_VETTING_0021).
     *
     * <p>Deliberately configuration rather than API-managed runtime state: vetting policy must be
     * attributable per chain run (GW_VETTING_0012), and a policy that changes only by deploy — its digest
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
     * Continuous re-vetting of already-approved content (GW_VETTING_0012-GW_VETTING_0014).
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

    /** What a re-vetting violation does to the snapshot it was found on (GW_VETTING_0013, GW_VETTING_0014). */
    public enum RevetMode {

        /**
         * Record and announce, change nothing. The violation lands in the ledger, the lifecycle
         * event goes out, and the portal shows it — but the snapshot stays approved and published.
         * The default, and the way to measure a policy before it can take content away.
         */
        WARN,

        /** Revoke the snapshot and stop serving it (GW_VETTING_0013). */
        ENFORCE
    }

    /**
     * The approval gate's own settings (GW_APPROVAL_0011). Only the separation-of-duties rule lives here so
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
     * Separation of duties on approval (GW_APPROVAL_0010, GW_APPROVAL_0011): whether a reviewer who is also the
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

    /** What a detected four-eyes conflict does to the approval that raised it (GW_APPROVAL_0011). */
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
     * Snapshot retention (GW_RETENTION_0001–GW_RETENTION_0004). {@code enabled=false} — the default — stops both
     * scheduled passes: an upgrade never deletes anything until an operator opts in, while the
     * on-demand endpoints stay available for a dry run.
     *
     * <p>{@code stagingRefMaxAge} belongs to the compaction pass's sweep of publication staging
     * references (GW_FACADE_0019) rather than to any per-marketplace policy: it describes how long a
     * publication may take, which is a property of the storage and the estate's snapshot sizes,
     * not of what any one marketplace is allowed to keep.
     */
    public record Retention(
            Boolean enabled,
            Duration pollInterval,
            Duration compactionInterval,
            Integer batchSize,
            Duration stagingRefMaxAge,
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
            // A day, because the only cost of being wrong upwards is disk and the cost of being
            // wrong downwards is a publication losing its objects mid-flight (GW_FACADE_0019). Nothing
            // needs it to be small: the reference reclaims nothing while it waits either way.
            if (stagingRefMaxAge == null) {
                stagingRefMaxAge = Duration.ofHours(24);
            }
            defaults = merge(defaults, FALLBACK);
            marketplaces = marketplaces == null ? Map.of() : Map.copyOf(marketplaces);
        }

        /**
         * Whether the staging-reference sweep runs at all. Zero or negative switches it off rather
         * than making every staging reference instantly eligible — the same fail-safe reading
         * {@code held-max-age} gets, and for the same reason: the mis-typed value must not be the
         * one that deletes.
         */
        public boolean stagingSweepEnabled() {
            return !stagingRefMaxAge.isZero() && !stagingRefMaxAge.isNegative();
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

    /** Audit ledger export (GW_AUDIT_0003–GW_AUDIT_0005); {@code enabled=false} stops the exporter poller only. */
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

    /** Outbound lifecycle webhook dispatch (GW_WEBHOOK_0003); {@code enabled=false} stops the poller only. */
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

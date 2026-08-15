package io.github.jimisola.skillsgateway.config;

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
        Vetting vetting) {

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
            vetting = new Vetting(null, null, null, null);
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
     */
    public record Vetting(
            Duration timeout, Long maxFileBytes, Duration waiverSweepInterval, Integer waiverSweepBatchSize) {

        public Vetting {
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
        }
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

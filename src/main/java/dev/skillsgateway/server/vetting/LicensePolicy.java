package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The configured license allow/ban lists and their one evaluation rule (GW_0090). Shared by the
 * connector (which turns evaluations into findings that gate approval) and the report endpoint
 * (which states them per detection), so the two can never disagree.
 *
 * <p>The ban list is checked before the allow list: a license on both is reported as banned, the
 * stronger statement. Severities encode the default-safe policy — with no allow list configured,
 * an unknown or missing license only warns; once one is configured, anything not demonstrably on
 * it blocks.
 */
final class LicensePolicy {

    private final SkillsGatewayProperties.License config;
    private final Set<String> allowed;
    private final Set<String> banned;

    LicensePolicy(SkillsGatewayProperties.License config) {
        this.config = config;
        this.allowed = lower(config.allowed());
        this.banned = lower(config.banned());
    }

    private static Set<String> lower(List<String> ids) {
        return ids.stream().map(id -> id.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    @Requirements({"GW_0090"})
    LicenseEvaluation evaluate(LicenseDetector.Detection detection) {
        if (detection.unknown()) {
            return LicenseEvaluation.UNKNOWN;
        }
        String id = detection.spdxId().toLowerCase(Locale.ROOT);
        if (banned.contains(id)) {
            return LicenseEvaluation.BANNED;
        }
        if (config.allowListConfigured() && !allowed.contains(id)) {
            return LicenseEvaluation.NOT_ALLOWED;
        }
        return LicenseEvaluation.OK;
    }

    /**
     * The connector's findings for a snapshot's detections: every identified license recorded
     * informationally, every policy objection as a blocking finding on its own rule id, unknown and
     * missing license as their own first-class states (GW_0089, GW_0090).
     */
    @Requirements({"GW_0089", "GW_0090"})
    List<Finding> findings(List<LicenseDetector.Detection> detections) {
        List<Finding> findings = new ArrayList<>();
        Severity failClosed = config.allowListConfigured() ? Severity.HIGH : Severity.LOW;
        for (LicenseDetector.Detection detection : detections) {
            if (!detection.unknown()) {
                findings.add(new Finding(
                        "license-detected",
                        Severity.INFO,
                        detection.location(),
                        "license %s detected%s".formatted(detection.spdxId(), declaredSuffix(detection))));
            }
            switch (evaluate(detection)) {
                case BANNED ->
                    findings.add(new Finding(
                            "license-banned",
                            Severity.CRITICAL,
                            detection.location(),
                            "license %s is on the configured ban list".formatted(detection.spdxId())));
                case NOT_ALLOWED ->
                    findings.add(new Finding(
                            "license-not-allowed",
                            Severity.HIGH,
                            detection.location(),
                            "license %s is not on the configured allow list".formatted(detection.spdxId())));
                case UNKNOWN ->
                    findings.add(new Finding(
                            "license-unknown",
                            failClosed,
                            detection.location(),
                            "no known license could be identified here%s".formatted(declaredSuffix(detection))));
                default -> {
                    // OK: identified and permitted — the license-detected record above is the answer.
                }
            }
        }
        if (detections.isEmpty()) {
            findings.add(new Finding(
                    "license-missing",
                    failClosed,
                    ".",
                    "the snapshot carries no license information: no license/copying file and no manifest"
                            + " license metadata"));
        }
        return findings;
    }

    private static String declaredSuffix(LicenseDetector.Detection detection) {
        return detection.declared() == null ? "" : " (declared as '%s')".formatted(detection.declared());
    }

    /**
     * Stable identity of the policy in force, stamped into the connector version so every recorded
     * chain run names the policy it ran under (GW_0049). {@code default} when nothing is
     * configured; otherwise a digest over the normalised, sorted lists.
     */
    String digest() {
        if (allowed.isEmpty() && banned.isEmpty()) {
            return "default";
        }
        String canonical = "allowed=%s|banned=%s"
                .formatted(
                        allowed.stream().sorted().collect(Collectors.joining(",")),
                        banned.stream().sorted().collect(Collectors.joining(",")));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

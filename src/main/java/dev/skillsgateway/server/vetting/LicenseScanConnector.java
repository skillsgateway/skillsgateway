package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Built-in connector: license compliance (GW_0093, GW_0094).
 *
 * <p>Detects the licenses a snapshot declares — deterministically, via {@link LicenseDetector} —
 * and evaluates them against the organisation's configured allow/ban lists. Violations are ordinary
 * vetting findings: they aggregate fail-closed, gate approval, are acceptable only by a scoped
 * expiring waiver on their rule id, and are re-examined by continuous re-vetting.
 *
 * <p><b>What it cannot do.</b> It matches known license texts and SPDX ids. A license the
 * fingerprint table does not know lands in the explicit {@code license-unknown} state rather than
 * being scored; SPDX expression algebra and dependency-level licenses are out of scope.
 */
@Component
public class LicenseScanConnector implements VettingConnector {

    private final LicensePolicy policy;

    public LicenseScanConnector(SkillsGatewayProperties properties) {
        this.policy = new LicensePolicy(properties.vetting().license());
    }

    @Override
    public String name() {
        return "license-scan";
    }

    @Override
    public int order() {
        return 300;
    }

    /**
     * Table version plus a digest of the policy in force: a changed allow/ban list changes the
     * recorded chain identity (GW_0049), so a changed answer about unchanged content is
     * attributable to the policy rather than guessed at.
     */
    @Override
    @Requirements({"GW_0094"})
    public String version() {
        return LicenseDetector.VERSION + "+policy-" + policy.digest();
    }

    @Override
    public String description() {
        return "Deterministic SPDX license detection over license/copying files and manifest metadata,"
                + " evaluated against the configured allow/ban lists. Exact matching only: an"
                + " unrecognized license text is reported as unknown, never scored.";
    }

    @Override
    @Requirements({"GW_0093", "GW_0094", "GW_0143"})
    public Verdict vet(SnapshotUnderVetting snapshot) {
        try {
            var detections = LicenseDetector.detect(snapshot);
            String summary = "detected %d license declaration(s) over license/copying files and manifest metadata;"
                            .formatted(detections.size())
                    + " evaluated against the configured allow/ban lists";
            return Verdict.of(policy.findings(detections), summary);
        } catch (IOException e) {
            // Reading the snapshot failed midway; the chain records this as an error, which blocks.
            throw new IllegalStateException("cannot read snapshot content", e);
        }
    }
}

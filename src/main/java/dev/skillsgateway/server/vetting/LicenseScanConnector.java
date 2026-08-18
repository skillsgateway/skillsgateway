package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
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

    private final SkillsGatewayProperties.License policy;

    public LicenseScanConnector(SkillsGatewayProperties properties) {
        this.policy = properties.vetting().license();
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
    public String version() {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public String description() {
        return "Deterministic SPDX license detection over license/copying files and manifest metadata,"
                + " evaluated against the configured allow/ban lists. Exact matching only: an"
                + " unrecognized license text is reported as unknown, never scored.";
    }

    @Override
    public Verdict vet(SnapshotUnderVetting snapshot) {
        throw new UnsupportedOperationException("not implemented");
    }
}

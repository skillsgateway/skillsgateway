package dev.skillsgateway.server.vetting;

import java.io.IOException;
import java.util.List;

/**
 * Deterministic license detection over a pinned snapshot (GW_0093): SPDX identifiers resolved from
 * license/copying files anywhere in the tree, {@code SPDX-License-Identifier} tags inside them, and
 * the license metadata fields of the marketplace manifest. Exact fingerprint matching only — no
 * scoring, no thresholds — so the same content under the same {@link #VERSION} always yields the
 * same detections; a source that identifies nothing is an explicit unknown, never a guess.
 */
final class LicenseDetector {

    /** Identity of the fingerprint table; bump on any change to what this class recognizes. */
    static final String VERSION = "1";

    private LicenseDetector() {}

    /** Where a detection came from. */
    enum Source {
        FILE,
        MANIFEST
    }

    /**
     * One license detection.
     *
     * @param source license file or manifest metadata field
     * @param location the file path, or {@code <manifest path>#<field>} for manifest metadata
     * @param spdxId the identified SPDX id, or {@code null} for the unknown-license state
     * @param declared the raw declared value for manifest metadata, {@code null} for files
     */
    record Detection(Source source, String location, String spdxId, String declared) {

        boolean unknown() {
            return spdxId == null;
        }
    }

    /** Every license detection in the snapshot, in tree order; empty means no license information. */
    static List<Detection> detect(SnapshotUnderVetting snapshot) throws IOException {
        throw new UnsupportedOperationException("not implemented");
    }
}

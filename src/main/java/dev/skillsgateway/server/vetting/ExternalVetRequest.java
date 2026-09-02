package dev.skillsgateway.server.vetting;

import java.util.List;

/**
 * The request body the gateway POSTs to an external vetting connector (GW_0144): the snapshot's
 * identity and a bundle of its scannable file content, the same view of the snapshot a built-in
 * connector walks. Quarantined content is never served through the facade, so the external service
 * cannot fetch it itself — the gateway ships it.
 *
 * @param snapshotId the snapshot row id the verdict will be recorded against
 * @param marketplace gateway-local marketplace name
 * @param sha the upstream commit SHA the snapshot is pinned to
 * @param files every file in the snapshot tree; a file over the per-file cap or one that is not
 *     UTF-8 text is present with {@code scanned=false} and null content, so a coverage gap is
 *     visible rather than silent
 */
public record ExternalVetRequest(long snapshotId, String marketplace, String sha, List<File> files) {

    /**
     * One file in the bundle.
     *
     * @param path repository-relative path
     * @param content the file's UTF-8 text, or null when {@code scanned} is false
     * @param scanned whether the content is present and text
     */
    public record File(String path, String content, boolean scanned) {}
}

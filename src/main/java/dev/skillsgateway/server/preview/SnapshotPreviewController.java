package dev.skillsgateway.server.preview;

import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.roles.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The reviewer's own eyes on a snapshot (GW_0080, GW_0081): the pinned commit's file tree, its
 * blobs as inert text, and the delta against what the marketplace currently serves.
 *
 * <p>These reads expose held quarantine content — the very material vetting flags — so, unlike
 * the open snapshot-metadata reads, they are privileged: admin or an approver of the snapshot's
 * marketplace, resolved on the gateway's side from the addressed snapshot (GW_0069's
 * confused-deputy-safe resolver). They live on the OIDC web surface only; nothing here touches
 * {@code /git/**} or changes what the facade serves.
 */
@RestController
@RequestMapping("/api")
public class SnapshotPreviewController {

    private final SnapshotPreviewService previewService;
    private final RoleService roleService;

    public SnapshotPreviewController(SnapshotPreviewService previewService, RoleService roleService) {
        this.previewService = previewService;
        this.roleService = roleService;
    }

    @GetMapping("/snapshots/{id}/files")
    @Tag(name = "Snapshot preview")
    @Operation(
            summary = "File tree of the pinned commit",
            description = "Every path in exactly the commit the snapshot pins, resolved through the quarantine"
                    + " repository's object store, capped at 2000 entries with an explicit marker when cut."
                    + " Privileged while role enforcement is enabled: admin or an approver of the snapshot's"
                    + " marketplace.")
    @ApiResponse(responseCode = "200", description = "Paths and sizes of the pinned commit's tree")
    @ApiResponse(
            responseCode = "403",
            description = "Role enforcement is enabled and the session holds no applicable role")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    public SnapshotPreviewService.FileTree files(@PathVariable long id, Authentication authentication) {
        roleService.requireApproverOfSnapshot(authentication, id);
        return previewService.files(id);
    }

    @GetMapping("/snapshots/{id}/file")
    @Tag(name = "Snapshot preview")
    @Operation(
            summary = "One file of the pinned commit",
            description = "One blob addressed strictly within the pinned commit's tree — a path the tree does"
                    + " not contain, traversal shapes included, is not found. Text is returned for rendering"
                    + " only, cut at 128 KiB with an explicit truncation marker; a blob detected as binary"
                    + " returns metadata without text. Privileged while role enforcement is enabled.")
    @ApiResponse(responseCode = "200", description = "Blob metadata, and its text unless binary")
    @ApiResponse(
            responseCode = "403",
            description = "Role enforcement is enabled and the session holds no applicable role")
    @ApiResponse(responseCode = "404", description = "Snapshot not found, or the path is not in the pinned tree")
    public SnapshotPreviewService.FileContent file(
            @PathVariable long id, @RequestParam String path, Authentication authentication) {
        roleService.requireApproverOfSnapshot(authentication, id);
        return previewService
                .file(id, path)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no such path in snapshot %d: %s".formatted(id, path)));
    }

    @GetMapping("/snapshots/{id}/diff")
    @Tag(name = "Snapshot preview")
    @Operation(
            summary = "Diff against the currently served commit",
            description = "Added, modified and removed paths between the pinned commit and the marketplace's"
                    + " currently served commit (the published repository's served tip), with a unified text"
                    + " diff per non-binary entry under the same size caps as file reads. When the marketplace"
                    + " serves nothing the baseline is null and every path is reported as added. Privileged"
                    + " while role enforcement is enabled.")
    @ApiResponse(responseCode = "200", description = "The delta a reviewer decides")
    @ApiResponse(
            responseCode = "403",
            description = "Role enforcement is enabled and the session holds no applicable role")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    public SnapshotPreviewService.SnapshotDiff diff(@PathVariable long id, Authentication authentication) {
        roleService.requireApproverOfSnapshot(authentication, id);
        return previewService.diff(id);
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ProblemDetail snapshotNotFound(SnapshotNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}

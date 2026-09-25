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
 * The reviewer's own eyes on a snapshot (GW_INGEST_0015, GW_INGEST_0016): the pinned commit's file tree, its
 * blobs as inert text, and the delta against what the marketplace currently serves. Each listing is
 * paged, so a snapshot of any size can be read to its end (GW_APPROVAL_0025).
 *
 * <p>These reads expose held quarantine content — the very material vetting flags — so, unlike
 * the open snapshot-metadata reads, they are privileged: admin or an approver of the snapshot's
 * marketplace, resolved on the gateway's side from the addressed snapshot (GW_AUTH_0011's
 * confused-deputy-safe resolver). They live on the OIDC web surface only; nothing here touches
 * {@code /git/**} or changes what the facade serves.
 */
@RestController
@RequestMapping("/api/v1")
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
            summary = "Paths of the pinned commit, or a path search over them",
            description = "The paths of exactly the commit the snapshot pins, in tree order, resolved through the"
                    + " quarantine repository's object store. With q, only the paths whose full path contains"
                    + " it, compared without regard to case. Paged: 2000 paths per response from offset, with"
                    + " total counting every match and nextOffset naming the next page. Privileged: admin or"
                    + " an approver of the snapshot's marketplace.")
    @ApiResponse(responseCode = "200", description = "A page of paths and sizes, with the total")
    @ApiResponse(responseCode = "400", description = "A negative offset")
    @ApiResponse(responseCode = "403", description = "The session holds no applicable role")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    public SnapshotPreviewService.FileTree files(
            @PathVariable long id,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int offset,
            Authentication authentication) {
        roleService.requireApproverOfSnapshot(authentication, id);
        return previewService.files(id, q, requireOffset(offset));
    }

    @GetMapping("/snapshots/{id}/tree")
    @Tag(name = "Snapshot preview")
    @Operation(
            summary = "One directory of the pinned commit",
            description = "The direct children of dir (the root when absent), directories first and then files,"
                    + " each by name. Each child carries its change against the marketplace's currently served"
                    + " commit, and paths the snapshot removes are listed too; a directory child carries the"
                    + " files beneath it and how many of them changed, and the response carries the same two"
                    + " totals for dir itself. Paged: 500 children per response from offset. A directory in"
                    + " neither tree, traversal shapes included, is not found. Privileged.")
    @ApiResponse(responseCode = "200", description = "A page of the directory's children, with totals")
    @ApiResponse(responseCode = "400", description = "A negative offset")
    @ApiResponse(responseCode = "403", description = "The session holds no applicable role")
    @ApiResponse(
            responseCode = "404",
            description = "Snapshot not found, or dir is not a directory of the pinned or served tree")
    public SnapshotPreviewService.DirectoryListing tree(
            @PathVariable long id,
            @RequestParam(required = false) String dir,
            @RequestParam(defaultValue = "0") int offset,
            Authentication authentication) {
        roleService.requireApproverOfSnapshot(authentication, id);
        return previewService
                .tree(id, dir, requireOffset(offset))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no such directory in snapshot %d: %s".formatted(id, dir)));
    }

    @GetMapping("/snapshots/{id}/file")
    @Tag(name = "Snapshot preview")
    @Operation(
            summary = "One file of the pinned commit",
            description = "One blob addressed strictly within the pinned commit's tree — a path the tree does"
                    + " not contain, traversal shapes included, is not found. Text is returned for rendering"
                    + " only, cut at 128 KiB with an explicit truncation marker; a blob detected as binary"
                    + " returns metadata without text. Privileged.")
    @ApiResponse(responseCode = "200", description = "Blob metadata, and its text unless binary")
    @ApiResponse(responseCode = "403", description = "The session holds no applicable role")
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
                    + " serves nothing the baseline is null and every path is reported as added. Paged: 500"
                    + " entries per response from offset; total and summary count the whole diff, not the page."
                    + " path narrows the diff as a git pathspec does, to one file or everything beneath one"
                    + " directory. Privileged.")
    @ApiResponse(responseCode = "200", description = "A page of the delta a reviewer decides, with its totals")
    @ApiResponse(responseCode = "400", description = "A negative offset")
    @ApiResponse(responseCode = "403", description = "The session holds no applicable role")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    public SnapshotPreviewService.SnapshotDiff diff(
            @PathVariable long id,
            @RequestParam(required = false) String path,
            @RequestParam(defaultValue = "0") int offset,
            Authentication authentication) {
        roleService.requireApproverOfSnapshot(authentication, id);
        return previewService.diff(id, path, requireOffset(offset));
    }

    private static int requireOffset(int offset) {
        if (offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset must not be negative");
        }
        return offset;
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ProblemDetail snapshotNotFound(SnapshotNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}

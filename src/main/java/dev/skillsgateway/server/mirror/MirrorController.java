package dev.skillsgateway.server.mirror;

import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MirrorController {

    private final ForgeMirrorService mirror;
    private final RoleService roleService;

    public MirrorController(ForgeMirrorService mirror, RoleService roleService) {
        this.mirror = mirror;
        this.roleService = roleService;
    }

    /**
     * Administrator-only, not an auditor read: it names an outbound integration target and the
     * state of the credential's last use, which is deployment infrastructure rather than a record
     * of what the gateway served to whom. It is on the machine API's unreachable list for the same
     * reason.
     */
    @GetMapping("/mirror/drift")
    @Tag(name = "Mirror")
    @Operation(
            summary = "Compare the read-only forge mirror against what the facade serves",
            description = "Reads the mirror's references now and diffs them against published storage."
                    + " The mirror is a browsing convenience and never a serving surface, so a mirror that"
                    + " is missing, stale or unreachable says nothing about what clients receive. A mirror"
                    + " the gateway cannot read is reported as unreachable and never as in sync.")
    @ApiResponse(responseCode = "200", description = "The comparison, or a disabled report when no mirror is set up")
    @ApiResponse(responseCode = "403", description = "Not an administrator")
    @Requirements({"GW_0172"})
    public MirrorReport drift(Authentication authentication) {
        roleService.requireAdmin(authentication);
        return mirror.report();
    }

    /**
     * Reconcile the mirror now and answer with the result (GW_0192).
     *
     * <p>The concrete answer to "what does an operator do about drift", which before this was
     * "approve or revoke something, or restart the gateway" — both worse than the problem. It is
     * the one mirror path that waits for the forge, because waiting is the request: an operator who
     * has just fixed an outage or rotated a rejected credential is asking whether the mirror is
     * right <em>now</em>. Nothing about approval, revocation or what the facade serves is on this
     * path, so GW_0170 is untouched, and the wait is bounded by the mirror's own timeout and
     * attempt count.
     *
     * <p>{@code POST} because it changes a remote system; administrator-only and unreachable by any
     * machine-credential scope for the same reason the drift report is.
     */
    @PostMapping("/mirror/reconcile")
    @Tag(name = "Mirror")
    @Operation(
            summary = "Reconcile the read-only forge mirror with what the facade serves",
            description = "Pushes the served reference set and removes whatever the mirror still holds outside it,"
                    + " then answers with the resulting comparison. Use it after fixing a forge outage or rotating"
                    + " a rejected credential rather than waiting for the recurring reconciliation. It cannot"
                    + " affect what the facade serves, and a mirror that stays unreachable is reported as"
                    + " unreachable rather than failing the request.")
    @ApiResponse(responseCode = "200", description = "The comparison after reconciling, or a disabled report")
    @ApiResponse(responseCode = "403", description = "Not an administrator")
    @Requirements({"GW_0192"})
    public MirrorReport reconcile(Authentication authentication) {
        roleService.requireAdmin(authentication);
        return mirror.reconcileNow(REASON);
    }

    /** The reason recorded on the ledger for a reconciliation an administrator asked for. */
    private static final String REASON = "administrator-request";
}

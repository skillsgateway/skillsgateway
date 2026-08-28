package dev.skillsgateway.server.admin;

import dev.skillsgateway.server.auth.MachineApiAuthentication;
import dev.skillsgateway.server.estate.EstateReconciler;
import dev.skillsgateway.server.persistence.ActorType;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.sync.SyncService;
import dev.skillsgateway.server.vetting.RevetService;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.Requirements;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Appends administrative actions (GW_0022) to the same append-only ledger as facade fetches:
 * registration, ingestion, approval decisions, and token lifecycle, each with the acting identity.
 *
 * <p>It is also the <b>one place</b> an entry's actor kind is decided (GW_0128). Deriving it here,
 * from the authentication that is actually on the request, rather than passing it from each of the
 * two dozen call sites, is what keeps a denormalised column from drifting: there is exactly one
 * line that can be wrong. The gateway's own actors — reconciliation, the schedulers, the waiver
 * sweep — have no authentication to derive from and declare {@link ActorType#SYSTEM} explicitly
 * through {@link #recordAs}, which turns yesterday's magic strings into a stated vocabulary.
 */
@Component
public class AdminAuditLogger {

    /** Ledger source marker distinguishing admin actions from git facade fetches. */
    private static final String SOURCE = "admin";

    /**
     * The gateway's own actor names, declared (GW_0128). These already existed — {@code
     * config-reconciler}, {@code scheduler}, {@code webhook}, the re-vetting sweep and the waiver
     * expiry — as magic strings threaded through service calls into the {@code principal} column,
     * distinguishable only by string comparison and enforced by nothing.
     *
     * <p>Listing them here does not make the comparison go away; it makes it happen <b>once</b>,
     * against a declared set, in the one place that types a ledger entry. What changes for every
     * consumer of the ledger is that they no longer compare strings at all: {@code actor_type} is
     * the column they query, and it is a database type that admits three values.
     *
     * <p>The constants are referenced rather than re-spelled, so renaming one is a compile error
     * here rather than a silently mistyped entry.
     */
    private static final Set<String> SYSTEM_ACTORS = Set.of(
            EstateReconciler.ACTOR,
            SyncService.SCHEDULER_ACTOR,
            SyncService.WEBHOOK_ACTOR,
            RevetService.SWEEP_ACTOR,
            WaiverService.SYSTEM_ACTOR);

    private final FetchLogRepository fetchLogRepository;

    public AdminAuditLogger(FetchLogRepository fetchLogRepository) {
        this.fetchLogRepository = fetchLogRepository;
    }

    @Requirements({"GW_0022"})
    public void record(String principal, String marketplace, String event, String sha) {
        record(principal, marketplace, event, sha, null);
    }

    /** As {@link #record}, carrying the entry's free-text qualifier (a vetting outcome or reason). */
    @Requirements({"GW_0022", "GW_0043", "GW_0128"})
    public void record(String principal, String marketplace, String event, String sha, String detail) {
        Authentication authentication = current();
        MachineApiAuthentication machine = machineActor(authentication, principal);
        ActorType actorType = machine != null
                ? ActorType.MACHINE
                : SYSTEM_ACTORS.contains(principal) ? ActorType.SYSTEM : ActorType.HUMAN;
        fetchLogRepository.append(
                SOURCE,
                principal,
                marketplace,
                event,
                null,
                sha,
                detail,
                // A machine entry names the credential that produced it, so a leak trace has the
                // same per-credential resolution the facade already has; NULL elsewhere, as today.
                machine == null ? null : machine.token().id(),
                actorType);
    }

    /**
     * As {@link #record}, for an actor the gateway is rather than one it authenticated (GW_0128) —
     * {@code config-reconciler}, {@code scheduler}, {@code webhook}, the re-vetting sweep and the
     * waiver expiry. These have no authentication to derive a kind from, so they state it.
     */
    @Requirements({"GW_0022", "GW_0128"})
    public void recordAs(ActorType actorType, String principal, String marketplace, String event, String detail) {
        fetchLogRepository.append(SOURCE, principal, marketplace, event, null, null, detail, null, actorType);
    }

    /**
     * The machine credential on this request, if the entry is genuinely about it. The principal
     * comparison matters: an administrator minting a credential is a human entry naming a human,
     * and an entry written while a machine request is in flight but attributed to someone else is
     * not the machine's action.
     */
    private static MachineApiAuthentication machineActor(Authentication authentication, String principal) {
        if (authentication instanceof MachineApiAuthentication machine
                && machine.getName().equals(principal)) {
            return machine;
        }
        return null;
    }

    private static Authentication current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication;
    }
}

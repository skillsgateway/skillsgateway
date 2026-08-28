package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.sync.SyncService;
import dev.skillsgateway.server.vetting.WaiverEvaluation;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Separation of duties on approval (GW_0096, GW_0097), and the only place the rule lives.
 *
 * <p>The question it answers is narrow and worth stating precisely: not <em>may this principal
 * approve here</em> — that is the role model's decision, made before this gate ever runs — but
 * <em>may this particular principal approve this particular snapshot</em>, given what they already
 * did to put its content in front of themselves.
 *
 * <p>Three supply-side acts count, and they are the three ways one identity can walk content all
 * the way from an upstream to the facade unaccompanied: registering the marketplace it comes from,
 * triggering the ingestion that pinned it, and authoring a waiver the approval leans on. The third
 * is not an afterthought — it is the trivial bypass. Without it a reviewer refused for having
 * ingested a snapshot could waive the finding that blocks a second copy and approve that instead.
 *
 * <p>Pure over its inputs and free of both persistence and HTTP, so the rule can be exercised
 * exhaustively; the caller supplies the snapshot, the marketplace and the waivers that were
 * actually applied.
 */
@Component
public class FourEyesGate {

    /** The reviewer triggered the ingestion that produced this snapshot. */
    public static final String ROLE_INGESTED_BY = "ingested-by";

    /** The reviewer registered the marketplace the snapshot came from. */
    public static final String ROLE_REGISTERED_BY = "registered-by";

    /** The reviewer wrote a waiver this approval relies on. */
    public static final String ROLE_WAIVER_AUTHOR = "waiver-author";

    /** Ledger event for a detected conflict, written in both modes (GW_0097). */
    public static final String EVENT_CONFLICT = "four-eyes-conflict";

    /**
     * Identities that are triggers rather than people (GW_0096). They are written to
     * {@code snapshots.ingested_by} so attribution stays honest — the ledger and the snapshot agree
     * on what caused the ingestion — but they are excluded from comparison, because a scheduled
     * poll or a forge webhook is nobody's judgement about the content. A snapshot the sweep brought
     * in is approvable by anyone the role model allows, which is the ordinary case in an estate on
     * automatic sync.
     */
    private static final Set<String> NON_HUMAN_ACTORS = Set.of(SyncService.SCHEDULER_ACTOR, SyncService.WEBHOOK_ACTOR);

    private final SkillsGatewayProperties properties;

    public FourEyesGate(SkillsGatewayProperties properties) {
        this.properties = properties;
    }

    public SkillsGatewayProperties.FourEyesMode mode() {
        return properties.approval().fourEyes().mode();
    }

    public boolean enforcing() {
        return properties.approval().fourEyes().enforcing();
    }

    /**
     * Every supply-side act the reviewer performed on this snapshot, in the order they happen in
     * the snapshot's life: registration, ingestion, then the waivers the approval leans on.
     *
     * @param applied the waivers in force for this approval — the ones {@code WaiverEvaluation}
     *     actually suppressed findings with, never the marketplace's whole waiver set: a waiver
     *     that is suppressing nothing is not something this approval relies on
     */
    @Requirements({"GW_0096"})
    public List<FourEyesConflictException.Conflict> conflicts(
            Snapshot snapshot, Marketplace marketplace, List<WaiverEvaluation.Suppression> applied, String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            return List.of();
        }
        List<FourEyesConflictException.Conflict> conflicts = new ArrayList<>();
        if (marketplace != null && sameIdentity(marketplace.registeredBy(), reviewer)) {
            conflicts.add(new FourEyesConflictException.Conflict(ROLE_REGISTERED_BY, reviewer, null));
        }
        if (sameIdentity(snapshot.ingestedBy(), reviewer)) {
            conflicts.add(new FourEyesConflictException.Conflict(ROLE_INGESTED_BY, reviewer, null));
        }
        // One conflict per waiver, not per suppressed finding: a single acceptance covering four
        // findings is one act, and reporting it four times would bury the other roles beside it.
        Set<Long> seen = new LinkedHashSet<>();
        for (WaiverEvaluation.Suppression suppression :
                applied == null ? List.<WaiverEvaluation.Suppression>of() : applied) {
            if (sameIdentity(suppression.approvedBy(), reviewer) && seen.add(suppression.waiverId())) {
                conflicts.add(
                        new FourEyesConflictException.Conflict(ROLE_WAIVER_AUTHOR, reviewer, suppression.waiverId()));
            }
        }
        return List.copyOf(conflicts);
    }

    /**
     * Exact string equality, with the two trigger constants excluded and an unrecorded actor
     * treated as nobody.
     *
     * <p>Exactness is a deliberate limitation rather than an oversight. Every identity compared
     * here originates from the same {@code Authentication#getName()} on the same identity provider,
     * so the formats are uniform; a rule that tried to be clever about case or shape would start
     * matching principals that are not the same person, and a separation-of-duties rule that
     * over-matches locks reviewers out of content they had nothing to do with.
     */
    private static boolean sameIdentity(String actor, String reviewer) {
        return actor != null && !NON_HUMAN_ACTORS.contains(actor) && actor.equals(reviewer);
    }

    /**
     * The gate itself: refuses in {@code enforce} mode, returns the conflicts in {@code warn} mode
     * so the caller can record them. Both modes detect identically — the mode decides only what
     * happens next, which is what keeps warn mode an honest rehearsal for enforcement.
     */
    @Requirements({"GW_0096", "GW_0097"})
    public List<FourEyesConflictException.Conflict> require(
            Snapshot snapshot, Marketplace marketplace, List<WaiverEvaluation.Suppression> applied, String reviewer) {
        List<FourEyesConflictException.Conflict> conflicts = conflicts(snapshot, marketplace, applied, reviewer);
        if (!conflicts.isEmpty() && enforcing()) {
            throw new FourEyesConflictException(snapshot.id(), conflicts);
        }
        return conflicts;
    }

    /** Renders conflicts for the audit ledger's detail column. */
    public static String describe(List<FourEyesConflictException.Conflict> conflicts) {
        List<String> parts = new ArrayList<>(conflicts.size());
        for (FourEyesConflictException.Conflict conflict : conflicts) {
            parts.add(
                    conflict.waiverId() == null
                            ? conflict.role()
                            : "%s(waiver=%d)".formatted(conflict.role(), conflict.waiverId()));
        }
        return String.join(",", new LinkedHashSet<>(parts));
    }

    /**
     * What the portal asks before it offers the Approve button: the mode in force, the conflicts
     * this reviewer would raise, and whether they would be refused.
     *
     * <p>Answered by the server rather than derived in the browser on purpose. The waiver-author
     * clause depends on which waivers the effective-outcome evaluation actually applies, and a
     * second implementation of that in the portal would be a rule that can disagree with the one
     * that decides.
     */
    @Schema(description = "Whether the four-eyes rule would refuse this reviewer's approval of this snapshot")
    public record FourEyesCheck(
            @Schema(
                    description = "The configured mode",
                    allowableValues = {"WARN", "ENFORCE"})
            SkillsGatewayProperties.FourEyesMode mode,

            @Schema(description = "The supply-side acts this reviewer performed on this snapshot")
            List<FourEyesConflictException.Conflict> conflicts,

            @Schema(description = "True when the mode is enforce and there is at least one conflict")
            boolean refused) {}
}

package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.vetting.ChainStaleness;
import dev.skillsgateway.server.vetting.Finding;
import dev.skillsgateway.server.vetting.Severity;
import dev.skillsgateway.server.vetting.VerdictState;
import dev.skillsgateway.server.vetting.VettingChain;
import dev.skillsgateway.server.vetting.VettingRepository;
import dev.skillsgateway.server.vetting.WaiverEvaluation;
import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whether a snapshot may be approved with no person: its candidacy (GW_APPROVAL_0044.5,
 * GW_APPROVAL_0044.6) and its vetting (GW_APPROVAL_0044.7), as functions of data so every branch is
 * testable without a database. A refusal is a stable reason, the same on every attempt while
 * nothing changes, because the stored reason is what deduplicates the ledger's skips.
 */
public final class AutoApprovalEligibility {

    private AutoApprovalEligibility() {}

    /**
     * @param newestId the marketplace's newest snapshot that is not deleted
     * @param closureMembers how many external sources the snapshot's recorded closure resolved
     * @return the reason it is not a candidate, or {@code null} when it is
     */
    @Requirements({"GW_APPROVAL_0044.5", "GW_APPROVAL_0044.6"})
    public static String candidacy(Snapshot snapshot, long newestId, int closureMembers) {
        if (snapshot.deleted()) {
            return "deleted: the snapshot is deleted";
        }
        if (!Snapshot.HELD.equals(snapshot.state())) {
            return "not-held: the snapshot is " + snapshot.state();
        }
        if (snapshot.id() != newestId) {
            return "not-newest: snapshot %d is newer".formatted(newestId);
        }
        if (closureMembers > 0) {
            return "external-source: the snapshot resolves %d external plugin source%s"
                    .formatted(closureMembers, closureMembers == 1 ? "" : "s");
        }
        return null;
    }

    /**
     * @param run the snapshot's latest run, or {@code null}
     * @param baselineRun the served baseline's latest run, or {@code null} when nothing is served
     * @param required the vetters that must not be {@code DISABLED}
     * @param everyVetter whether {@code required-vetters} is unset, so no verdict may be {@code DISABLED}
     */
    public record VettingInputs(
            ChainStaleness.State chain,
            VettingRepository.Run run,
            WaiverEvaluation.Effect effect,
            VettingRepository.Run baselineRun,
            List<String> required,
            boolean everyVetter) {}

    /**
     * @param refusal the first failing check, or {@code null} when the vetting qualifies
     * @param worst the worst severity among the run's findings, or {@code null} when it has none
     * @param waiverIds the waivers suppressing a finding in the run
     * @param disabledVetters the vetters switched off, which a relaxed {@code required-vetters} allowed
     */
    public record Vetting(String refusal, Severity worst, List<Long> waiverIds, List<String> disabledVetters) {

        public boolean qualifies() {
            return refusal == null;
        }
    }

    /** Findings that say a surface was not read, so a clean verdict over it means nothing. */
    static final Set<String> UNREAD_SURFACE =
            Set.of("hook-config-unreadable", "hook-target-unscanned", "too-many-plugin-roots");

    private static final Set<VerdictState> DID_NOT_RUN =
            EnumSet.of(VerdictState.NOT_REACHED, VerdictState.ERROR, VerdictState.PENDING);

    @Requirements({"GW_APPROVAL_0044.7"})
    public static Vetting vetting(VettingInputs inputs) {
        VettingRepository.Run run = inputs.run();
        if (run == null) {
            return refused("no-run: the snapshot has not been vetted");
        }
        if (inputs.chain() != ChainStaleness.State.IN_FORCE) {
            return refused("chain-not-in-force: the run's chain is " + inputs.chain());
        }
        List<String> disabled = new ArrayList<>();
        for (VettingRepository.VerdictView verdict : run.verdicts()) {
            if (DID_NOT_RUN.contains(verdict.state())) {
                return refused("vetter-not-run: %s is %s".formatted(verdict.vetter(), verdict.state()));
            }
            if (verdict.state() == VerdictState.DISABLED) {
                if (inputs.everyVetter() || inputs.required().contains(verdict.vetter())) {
                    return refused("required-vetter-disabled: " + verdict.vetter());
                }
                disabled.add(verdict.vetter());
            }
        }
        for (String required : inputs.required()) {
            if (run.stateOf(required).isEmpty()) {
                return refused("required-vetter-absent: %s is not in the chain".formatted(required));
            }
        }
        VettingChain.Outcome outcome = inputs.effect().outcome();
        if (outcome != VettingChain.Outcome.CLEAR && outcome != VettingChain.Outcome.CLEAR_WITH_WAIVERS) {
            return refused("blocked: the effective outcome is " + outcome);
        }
        Set<Long> waiverIds = new TreeSet<>();
        for (WaiverEvaluation.Suppression suppression : inputs.effect().suppressions()) {
            waiverIds.add(suppression.waiverId());
            for (Finding finding : findingsOf(run, suppression)) {
                if (finding.severity().compareTo(Severity.HIGH) >= 0 && !inBaseline(inputs.baselineRun(), finding)) {
                    return refused("waived-finding-new: %s at %s is not in the served baseline in that content"
                            .formatted(finding.id(), finding.location()));
                }
            }
        }
        Severity worst = null;
        for (VettingRepository.VerdictView verdict : run.verdicts()) {
            for (Finding finding : verdict.findings()) {
                if (UNREAD_SURFACE.contains(finding.id())) {
                    return refused("unread-surface: %s at %s".formatted(finding.id(), finding.location()));
                }
                if (worst == null || finding.severity().compareTo(worst) > 0) {
                    worst = finding.severity();
                }
            }
        }
        return new Vetting(null, worst, List.copyOf(waiverIds), List.copyOf(disabled));
    }

    private static Vetting refused(String reason) {
        return new Vetting(reason, null, List.of(), List.of());
    }

    private static List<Finding> findingsOf(VettingRepository.Run run, WaiverEvaluation.Suppression suppression) {
        List<Finding> found = new ArrayList<>();
        for (VettingRepository.VerdictView verdict : run.verdicts()) {
            if (!verdict.vetter().equals(suppression.vetter())) {
                continue;
            }
            for (Finding finding : verdict.findings()) {
                if (finding.id().equals(suppression.ruleId())
                        && Objects.equals(finding.location(), suppression.location())) {
                    found.add(finding);
                }
            }
        }
        return found;
    }

    /**
     * The same rule in the same blob of the served baseline's latest run: content already served
     * with the finding in it, whether a person or an automatic approval served it.
     */
    private static boolean inBaseline(VettingRepository.Run baseline, Finding finding) {
        if (baseline == null || finding.content() == null) {
            return false;
        }
        return baseline.verdicts().stream()
                .flatMap(verdict -> verdict.findings().stream())
                .anyMatch(prior ->
                        prior.id().equals(finding.id()) && finding.content().equals(prior.content()));
    }
}

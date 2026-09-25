package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Container-free verification of collapsed finding groups and the waiver that covers one
 * (GW_VETTING_0041, GW_VETTING_0042, GW_APPROVAL_0022).
 *
 * <p>A group waiver is a way past the approval gate, so most of this class attacks it: every
 * near-miss — another blob, another line, another rule, another commit, a finding the gateway
 * could not tie to a blob, a lapsed or revoked waiver — must suppress nothing.
 */
class FindingGroupWaiverTests {

    private static final String SHA = "1111111111111111111111111111111111111111";
    private static final String OTHER_SHA = "2222222222222222222222222222222222222222";
    private static final String BLOB = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER_BLOB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String RULE = "concealment-instruction";
    private static final String MESSAGE = "the instructions tell the agent to conceal its actions";
    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");

    private static Finding finding(String rule, String location, String blob) {
        return new Finding(rule, Severity.HIGH, location, MESSAGE, blob);
    }

    private static Waiver groupWaiver(String blob, Integer line) {
        return waiver(RULE, WaiverScope.SNAPSHOT, SHA, blob, line, NOW.plus(Duration.ofDays(1)), null);
    }

    private static Waiver waiver(
            String rule, WaiverScope scope, String value, String blob, Integer line, Instant expires, Instant revoked) {
        return new Waiver(
                1L,
                1L,
                "m",
                rule,
                scope,
                value,
                "vendored copy of a reviewed file",
                "alice",
                NOW.minus(Duration.ofDays(1)),
                expires,
                revoked,
                revoked == null ? null : "bob",
                null,
                blob,
                line);
    }

    private static VettingRepository.Run run(List<Finding> findings) {
        return new VettingRepository.Run(
                1L,
                1L,
                VettingRepository.TRIGGER_INGESTION,
                VettingChain.Outcome.BLOCKED,
                NOW,
                NOW,
                "prompt-injection@1",
                List.of(new VettingRepository.VerdictView(
                        1L, "prompt-injection", 0, Verdict.of(findings).state(), null, null, findings)));
    }

    // ---- collapsing (GW_VETTING_0041) ----

    @Test
    @SVCs({"SVC_GW_VETTING_0041"})
    void findingsOnTheSameBlobAndLineCollapseIntoOneGroupWithEveryLocation() {
        List<FindingGroup> groups = FindingGroup.of(List.of(
                finding(RULE, "a/SKILL.md:7", BLOB),
                finding(RULE, "b/SKILL.md:7", BLOB),
                finding(RULE, "c/SKILL.md:7", BLOB)));

        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.ruleId()).isEqualTo(RULE);
            assertThat(group.content()).isEqualTo(BLOB);
            assertThat(group.line()).isEqualTo(7);
            assertThat(group.locations()).containsExactly("a/SKILL.md:7", "b/SKILL.md:7", "c/SKILL.md:7");
        });
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0041"})
    void findingsDifferingInBlobLineRuleOrIdentityNeverCollapse() {
        List<FindingGroup> groups = FindingGroup.of(List.of(
                finding(RULE, "a/SKILL.md:7", BLOB),
                finding(RULE, "b/SKILL.md:7", OTHER_BLOB), // other content
                finding(RULE, "a/SKILL.md:9", BLOB), // other line of the same content
                finding("instruction-override", "a/SKILL.md:7", BLOB), // other rule
                finding(RULE, "x/SKILL.md:7", null), // not tied to a blob
                finding(RULE, "y/SKILL.md:7", null)));

        assertThat(groups).hasSize(6).allSatisfy(group -> assertThat(group.locations())
                .hasSize(1));
    }

    // ---- the group waiver (GW_VETTING_0042) ----

    @Test
    @SVCs({"SVC_GW_VETTING_0042"})
    void aGroupWaiverCoversEveryLocationOfItsGroupAndClearsTheGate() {
        VettingRepository.Run run =
                run(List.of(finding(RULE, "a/SKILL.md:7", BLOB), finding(RULE, "b/SKILL.md:7", BLOB)));

        WaiverEvaluation.Effect effect = WaiverEvaluation.evaluate(run, List.of(groupWaiver(BLOB, 7)), SHA, NOW);

        assertThat(effect.outcome()).isEqualTo(VettingChain.Outcome.CLEAR_WITH_WAIVERS);
        assertThat(effect.suppressions())
                .extracting(WaiverEvaluation.Suppression::location)
                .containsExactly("a/SKILL.md:7", "b/SKILL.md:7");
        assertThat(effect.uncovered()).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0042"})
    void aGroupWaiverNeverCoversContentThatDiffers() {
        Waiver waiver = groupWaiver(BLOB, 7);

        assertThat(waiver.covers(finding(RULE, "a/SKILL.md:7", BLOB), SHA, NOW)).isTrue();
        // A new file with the same rule on the same line, but different bytes.
        assertThat(waiver.covers(finding(RULE, "new/SKILL.md:7", OTHER_BLOB), SHA, NOW))
                .isFalse();
        // The same file, another line of it.
        assertThat(waiver.covers(finding(RULE, "a/SKILL.md:8", BLOB), SHA, NOW)).isFalse();
        // The same place, another rule.
        assertThat(waiver.covers(finding("instruction-override", "a/SKILL.md:7", BLOB), SHA, NOW))
                .isFalse();
        // The same content in another snapshot: the waiver dies with its commit.
        assertThat(waiver.covers(finding(RULE, "a/SKILL.md:7", BLOB), OTHER_SHA, NOW))
                .isFalse();
        // A finding the gateway could not tie to a blob.
        assertThat(waiver.covers(finding(RULE, "a/SKILL.md:7", null), SHA, NOW)).isFalse();
        // A finding with no line is not the group of a line, and the reverse.
        assertThat(waiver.covers(finding(RULE, "a/SKILL.md", BLOB), SHA, NOW)).isFalse();
        assertThat(groupWaiver(BLOB, null).covers(finding(RULE, "a/SKILL.md:7", BLOB), SHA, NOW))
                .isFalse();
        assertThat(groupWaiver(BLOB, null).covers(finding(RULE, "a/SKILL.md", BLOB), SHA, NOW))
                .isTrue();
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0042"})
    void aGroupWaiverLeavesEveryOtherGroupBlocking() {
        VettingRepository.Run run = run(List.of(
                finding(RULE, "a/SKILL.md:7", BLOB),
                finding(RULE, "b/SKILL.md:7", BLOB),
                finding(RULE, "c/OTHER.md:7", OTHER_BLOB)));

        WaiverEvaluation.Effect effect = WaiverEvaluation.evaluate(run, List.of(groupWaiver(BLOB, 7)), SHA, NOW);

        assertThat(effect.outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(effect.uncovered()).singleElement().satisfies(group -> {
            assertThat(group.locations()).containsExactly("c/OTHER.md:7");
            assertThat(group.content()).isEqualTo(OTHER_BLOB);
        });
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0042"})
    void aLapsedOrRevokedGroupWaiverCoversNothing() {
        Finding covered = finding(RULE, "a/SKILL.md:7", BLOB);
        Waiver expired = waiver(RULE, WaiverScope.SNAPSHOT, SHA, BLOB, 7, NOW.minusSeconds(1), null);
        Waiver revoked = waiver(RULE, WaiverScope.SNAPSHOT, SHA, BLOB, 7, NOW.plus(Duration.ofDays(1)), NOW);

        assertThat(expired.covers(covered, SHA, NOW)).isFalse();
        assertThat(revoked.covers(covered, SHA, NOW)).isFalse();
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0042"})
    void aGroupWaiverThatIsNotSnapshotScopedOrNamesNoBlobCannotBeBuilt() {
        Instant later = NOW.plus(Duration.ofDays(1));
        // A group qualifier on a path scope would outlive the commit it was judged on.
        assertThatThrownBy(() -> waiver(RULE, WaiverScope.PATH, "plugins/a", BLOB, 7, later, null))
                .isInstanceOf(IllegalArgumentException.class);
        for (String malformed :
                List.of("", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "abc", BLOB + ":7", BLOB + "0")) {
            assertThatThrownBy(() -> waiver(RULE, WaiverScope.SNAPSHOT, SHA, malformed, 7, later, null))
                    .as(malformed)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> waiver(RULE, WaiverScope.SNAPSHOT, SHA, BLOB, 0, later, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> waiver(RULE, WaiverScope.SNAPSHOT, SHA, null, 7, later, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0042"})
    void aWaiverOnTheRuleAloneIsUnchangedByGroups() {
        Waiver ruleWide = waiver(RULE, WaiverScope.SNAPSHOT, SHA, null, null, NOW.plus(Duration.ofDays(1)), null);

        assertThat(ruleWide.covers(finding(RULE, "a/SKILL.md:7", BLOB), SHA, NOW))
                .isTrue();
        assertThat(ruleWide.covers(finding(RULE, "c/OTHER.md:3", OTHER_BLOB), SHA, NOW))
                .isTrue();
        assertThat(ruleWide.covers(finding(RULE, "x/y.md:1", null), SHA, NOW)).isTrue();
    }

    // ---- the refusal names groups with path:line (GW_APPROVAL_0022) ----

    @Test
    @SVCs({"SVC_GW_APPROVAL_0022"})
    void theWorklistHasOneEntryPerGroupNamingEveryLocation() {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            findings.add(finding(RULE, "plugin-%02d/SKILL.md:7".formatted(i), BLOB));
        }
        findings.add(finding("instruction-override", "solo/SKILL.md:3", OTHER_BLOB));

        WaiverEvaluation.Effect effect = WaiverEvaluation.evaluate(run(findings), List.of(), SHA, NOW);

        assertThat(effect.uncovered()).hasSize(2);
        WaiverEvaluation.UncoveredFinding vendored = effect.uncovered().getFirst();
        assertThat(vendored.locations()).hasSize(12);
        assertThat(vendored.location()).isEqualTo("plugin-00/SKILL.md:7");
        assertThat(vendored.describe())
                .startsWith(RULE + " at plugin-00/SKILL.md:7, plugin-01/SKILL.md:7")
                .contains("plugin-09/SKILL.md:7")
                .doesNotContain("plugin-10/SKILL.md:7")
                .endsWith("(+2 more)");
        assertThat(effect.uncovered().get(1).describe()).isEqualTo("instruction-override at solo/SKILL.md:3");
    }
}

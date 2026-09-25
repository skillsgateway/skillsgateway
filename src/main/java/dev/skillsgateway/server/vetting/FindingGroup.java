package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Findings on identical content, shown once with every place that content occurs
 * (GW_VETTING_0041). A file vendored into nineteen plugins is one thing to judge, not nineteen.
 *
 * <p>Two findings collapse only when they name the same rule, severity and message on the same
 * line of the same git blob. A finding the gateway could not tie to a blob never collapses with
 * anything, because nothing proves its content equals another's.
 *
 * @param ruleId the rule every finding in the group carries
 * @param severity their shared severity
 * @param message their shared message
 * @param content the git blob they are in, or {@code null} for an uncollapsible single finding
 * @param line the line within that blob, or {@code null} when the findings carry none
 * @param locations every {@code path:line} the group stands for, in the order they were reported
 */
@Schema(description = "Findings on identical content (the same git blob and line), collapsed into one entry")
public record FindingGroup(
        @Schema(description = "Finding rule identifier") String ruleId,
        @Schema(description = "How much it matters") Severity severity,
        @Schema(description = "Reviewer-facing explanation") String message,

        @Schema(description = "Git blob id the findings are in; null when the finding could not be tied to one")
        String content,

        @Schema(description = "Line within that blob, or null")
        Integer line,

        @Schema(description = "Every path:line this group stands for; empty for an entry with no location")
        List<String> locations) {

    public FindingGroup {
        locations = List.copyOf(locations);
    }

    /** The findings grouped, in order of each group's first finding. */
    @Requirements({"GW_VETTING_0041"})
    public static List<FindingGroup> of(List<Finding> findings) {
        Map<Object, List<Finding>> groups = new LinkedHashMap<>();
        for (Finding finding : findings) {
            Object key = finding.content() == null
                    ? new Object() // identity: never equal to another finding's key
                    : List.of(
                            finding.id(),
                            finding.severity(),
                            finding.message(),
                            finding.content(),
                            Objects.toString(finding.line(), ""));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(finding);
        }
        return groups.values().stream().map(FindingGroup::from).toList();
    }

    private static FindingGroup from(List<Finding> members) {
        Finding first = members.getFirst();
        // A finding with no location (an aggregated entry) contributes none rather than a blank.
        List<String> locations =
                members.stream().map(Finding::location).filter(Objects::nonNull).toList();
        return new FindingGroup(
                first.id(), first.severity(), first.message(), first.content(), first.line(), locations);
    }
}

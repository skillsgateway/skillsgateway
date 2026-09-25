package dev.skillsgateway.server.vetting;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Measures the two pattern vetters over a local clone of a real marketplace, for the before/after
 * numbers of a precision change. Never part of a normal build: it runs only when
 * {@code -Dvetting.measure.repo=/path/to/clone} is given, and it never touches the network.
 *
 * <pre>./mvnw -q test -Dtest=VettingPrecisionMeasurement -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dvetting.measure.repo=/path/to/clone</pre>
 */
@EnabledIfSystemProperty(named = "vetting.measure.repo", matches = ".+")
class VettingPrecisionMeasurement {

    @Test
    void measure() throws Exception {
        File dir = new File(System.getProperty("vetting.measure.repo"));
        Repository repository =
                new FileRepositoryBuilder().findGitDir(dir).setMustExist(true).build();
        String sha = repository.resolve("HEAD").name();
        List<Vetter> vetters = List.of(new SecretScanVetter(), new PromptInjectionVetter());
        StringBuilder report = new StringBuilder("measured %s at %s%n".formatted(dir, sha));
        try (QuarantineSnapshot snapshot =
                new QuarantineSnapshot(1, "measure", sha, 1024L * 1024L, 64L << 20, repository)) {
            for (Vetter vetter : vetters) {
                Verdict verdict = snapshot.identify(vetter.vet(snapshot));
                List<Finding> high = verdict.findings().stream()
                        .filter(finding -> finding.severity().atLeast(Severity.HIGH))
                        .toList();
                List<FindingGroup> groups = FindingGroup.of(verdict.findings());
                List<FindingGroup> highGroups = FindingGroup.of(high);
                long info = verdict.findings().stream()
                        .filter(finding -> finding.severity() == Severity.INFO)
                        .count();
                report.append("%s: state=%s findings=%d high=%d info=%d groups=%d highGroups=%d%n"
                        .formatted(
                                vetter.name(),
                                verdict.state().stored(),
                                verdict.findings().size(),
                                high.size(),
                                info,
                                groups.size(),
                                highGroups.size()));
                report.append("  summary: %s%n".formatted(verdict.summary()));
                Map<String, Integer> byRule = new TreeMap<>();
                high.forEach(finding -> byRule.merge(finding.id(), 1, Integer::sum));
                report.append("  high by rule: %s%n".formatted(byRule));
                Map<String, List<String>> shown = new LinkedHashMap<>();
                for (FindingGroup group : highGroups) {
                    shown.computeIfAbsent(group.ruleId(), rule -> new ArrayList<>())
                            .add("%s (x%d)"
                                    .formatted(
                                            group.locations().getFirst(),
                                            group.locations().size()));
                }
                shown.forEach((rule, entries) -> report.append("  %s: %s%n".formatted(rule, entries)));
            }
        }
        System.out.println(report);
    }
}

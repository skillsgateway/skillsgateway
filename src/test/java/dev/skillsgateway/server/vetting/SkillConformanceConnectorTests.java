package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Container-free verification of {@link SkillConformanceConnector} against the pinned Agent Skills
 * specification (GW_0167). No Spring context and no database: the connector is a pure function from
 * a snapshot's SKILL.md files to findings, so this is where every branch of it is pinned.
 *
 * <p>The adversarial half matters as much as the conformance half. Frontmatter comes from a
 * quarantined upstream repository, so the tests drive an alias bomb, a nesting bomb and an oversize
 * document at the parser and assert that the loader's own limits refuse each one — by name, in the
 * finding's message, so the assertion cannot pass because something incidental happened to fail.
 */
class SkillConformanceConnectorTests {

    private static final String CONFORMANT = """
            ---
            name: hello
            description: Says hello. Use when the caller greets someone.
            license: Apache-2.0
            compatibility: Requires nothing at all
            metadata:
              author: example-org
              version: "1.0"
            allowed-tools: Bash(git:*) Read
            ---
            # Hello

            Say hello.
            """;

    private static final String PATH = "plugins/hello/skills/hello/SKILL.md";

    // --- The conformant case ---------------------------------------------------------------------

    @Test
    @SVCs({"SVC_GW_0167"})
    void aConformantSkillPassesWithNoFindings() {
        Verdict verdict = advisory().vet(snapshotOf(Map.of(PATH, CONFORMANT)));

        assertThat(verdict.state()).isEqualTo(VerdictState.PASS);
        assertThat(verdict.findings()).isEmpty();
        // A clean pass still states what it examined (GW_0143), naming the pin it examined against.
        assertThat(verdict.summary()).contains("scanned 1 SKILL.md file(s)").contains("agentskills-2026-08-04");
    }

    @Test
    @SVCs({"SVC_GW_0167"})
    void aSnapshotWithNoSkillsPassesAndSaysSo() {
        Verdict verdict =
                advisory().vet(snapshotOf(Map.of("README.md", "# nothing here", "docs/SKILL.md", "# not a skill")));

        assertThat(verdict.state()).isEqualTo(VerdictState.PASS);
        assertThat(verdict.findings()).isEmpty();
        // "Nothing to check" must be distinguishable from "did not run": the summary says which.
        assertThat(verdict.summary()).contains("scanned 0 SKILL.md file(s)");
    }

    @Test
    @SVCs({"SVC_GW_0167"})
    void onlyFilesInASkillDirectoryAreSelected() {
        assertThat(SkillConformanceConnector.skillDefinition("plugins/hello/skills/hello/SKILL.md"))
                .isTrue();
        assertThat(SkillConformanceConnector.skillDefinition("skills/hello/SKILL.md"))
                .isTrue();
        assertThat(SkillConformanceConnector.skillDefinition("plugins/hello/skills/hello/README.md"))
                .isFalse();
        assertThat(SkillConformanceConnector.skillDefinition("plugins/hello/SKILL.md"))
                .isFalse();
        assertThat(SkillConformanceConnector.skillDefinition("SKILL.md")).isFalse();
        assertThat(SkillConformanceConnector.skillDefinition("plugins/hello/skills/hello/deep/SKILL.md"))
                .isFalse();
    }

    // --- Every required field, missing and malformed ---------------------------------------------

    @Test
    @SVCs({"SVC_GW_0167"})
    void aSkillWithNoFrontmatterIsReportedAsSuch() {
        Verdict verdict = advisory().vet(snapshotOf(Map.of(PATH, "# Hello\n\nJust prose.\n")));

        assertThat(verdict.state()).isEqualTo(VerdictState.WARN);
        assertThat(verdict.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.id()).isEqualTo("skill-frontmatter-missing");
            assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(finding.location()).isEqualTo(PATH);
            assertThat(finding.message()).contains("does not open with a YAML frontmatter block");
        });
    }

    @Test
    @SVCs({"SVC_GW_0167"})
    void eachRequiredFieldMissingIsItsOwnFinding() {
        assertThat(rules("---\ndescription: Says hello. Use when greeting.\nname: hello\n---\n"))
                .isEmpty();
        assertThat(findings("---\ndescription: Says hello. Use when greeting.\n---\n"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.id()).isEqualTo("skill-field-missing");
                    assertThat(finding.message()).contains("'name'");
                });
        assertThat(findings("---\nname: hello\n---\n")).singleElement().satisfies(finding -> {
            assertThat(finding.id()).isEqualTo("skill-field-missing");
            assertThat(finding.message()).contains("'description'");
        });
        // An empty frontmatter block declares neither, and says so twice rather than once.
        assertThat(findings("---\n---\n"))
                .extracting(Finding::message)
                .anySatisfy(message -> assertThat(message).contains("'name'"))
                .anySatisfy(message -> assertThat(message).contains("'description'"));
        // A field present but with no value is missing, not invalid: YAML gives it a null.
        assertThat(findings("---\nname:\ndescription: Says hello. Use when greeting.\n---\n"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.id()).isEqualTo("skill-field-missing"));
    }

    @Test
    @SVCs({"SVC_GW_0167"})
    void eachConstraintOnNameIsCheckedSeparately() {
        assertThat(messages("---\nname: Hello\ndescription: d\n---\n"))
                .anySatisfy(message -> assertThat(message).contains("must be lowercase"));
        assertThat(messages("---\nname: hello_world\ndescription: d\n---\n"))
                .anySatisfy(message -> assertThat(message).contains("only letters, digits and hyphens"));
        assertThat(messages("---\nname: -hello\ndescription: d\n---\n"))
                .anySatisfy(message -> assertThat(message).contains("must not start or end with a hyphen"));
        assertThat(messages("---\nname: hel--lo\ndescription: d\n---\n"))
                .anySatisfy(message -> assertThat(message).contains("consecutive hyphens"));
        assertThat(messages("---\nname: %s\ndescription: d\n---\n".formatted("a".repeat(65))))
                .anySatisfy(message -> assertThat(message).contains("over the 64 character limit"));
        // The relational rule: the name has to be the directory the skill lives in.
        assertThat(messages("---\nname: goodbye\ndescription: d\n---\n"))
                .anySatisfy(message -> assertThat(message).contains("is 'goodbye' but the skill directory is 'hello'"));
        // A YAML scalar that is not a string is not a name.
        assertThat(messages("---\nname: 42\ndescription: d\n---\n"))
                .anySatisfy(message -> assertThat(message).contains("must be a string"));
        assertThat(rules("---\nname: 42\ndescription: d\n---\n")).contains("skill-field-invalid");
    }

    @Test
    @SVCs({"SVC_GW_0167"})
    void eachConstraintOnTheOtherFieldsIsCheckedSeparately() {
        String base = "---\nname: hello\ndescription: %s\n---\n";
        assertThat(messages(base.formatted("d".repeat(1025))))
                .anySatisfy(message -> assertThat(message).contains("over the 1024 character limit"));
        assertThat(messages("---\nname: hello\ndescription: d\ncompatibility: %s\n---\n".formatted("c".repeat(501))))
                .anySatisfy(message -> assertThat(message).contains("over the 500 character limit"));
        // metadata is a flat map of strings; a nested structure under it is not that.
        assertThat(messages("---\nname: hello\ndescription: d\nmetadata:\n  nested:\n    a: b\n---\n"))
                .anySatisfy(message -> assertThat(message).contains("not to nested structures"));
        assertThat(messages("---\nname: hello\ndescription: d\nmetadata: plain\n---\n"))
                .anySatisfy(message -> assertThat(message).contains("must be a mapping"));
        // allowed-tools may be a string or a list of scalars, and nothing else.
        assertThat(rules("---\nname: hello\ndescription: d\nallowed-tools: Read Bash\n---\n"))
                .isEmpty();
        assertThat(rules("---\nname: hello\ndescription: d\nallowed-tools:\n  - Read\n  - Bash\n---\n"))
                .isEmpty();
        assertThat(messages("---\nname: hello\ndescription: d\nallowed-tools:\n  key: value\n---\n"))
                .anySatisfy(message -> assertThat(message).contains("must be a string or a list of strings"));
    }

    // --- Unknown fields, by design, do not block --------------------------------------------------

    @Test
    @SVCs({"SVC_GW_0167"})
    void anUnknownFieldIsInformationalUnderBothPostures() {
        String md = "---\nname: hello\ndescription: Says hello. Use when greeting.\nauthor: someone\n---\n";

        Verdict advisory = advisory().vet(snapshotOf(Map.of(PATH, md)));
        assertThat(advisory.state()).isEqualTo(VerdictState.PASS);
        assertThat(advisory.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.id()).isEqualTo("skill-field-unknown");
            assertThat(finding.severity()).isEqualTo(Severity.INFO);
            assertThat(finding.message()).contains("'author'").contains("metadata");
        });

        // The pin is a snapshot of a moving document, so a field it has not heard of must never
        // become a block — not even for the operator who asked for enforcement.
        Verdict enforcing = enforcing().vet(snapshotOf(Map.of(PATH, md)));
        assertThat(enforcing.state()).isEqualTo(VerdictState.PASS);
        assertThat(enforcing.findings()).singleElement().satisfies(finding -> assertThat(finding.severity())
                .isEqualTo(Severity.INFO));
    }

    // --- Posture ----------------------------------------------------------------------------------

    @Test
    @SVCs({"SVC_GW_0167"})
    void thePostureDecidesWhetherADefectBlocks() {
        Map<String, String> files = Map.of(PATH, "---\nname: hello\n---\n");

        Verdict advisory = advisory().vet(snapshotOf(files));
        assertThat(advisory.state()).isEqualTo(VerdictState.WARN);
        assertThat(advisory.findings())
                .allSatisfy(finding -> assertThat(finding.severity()).isEqualTo(Severity.MEDIUM));

        Verdict enforcing = enforcing().vet(snapshotOf(files));
        assertThat(enforcing.state()).isEqualTo(VerdictState.FAIL);
        assertThat(enforcing.findings())
                .allSatisfy(finding -> assertThat(finding.severity()).isEqualTo(Severity.HIGH));

        // The posture is part of the recorded chain identity, alongside the pin and its digest.
        assertThat(advisory().version()).endsWith("+advisory").startsWith("agentskills-2026-08-04+schema-");
        assertThat(enforcing().version()).endsWith("+enforce");
    }

    @Test
    @SVCs({"SVC_GW_0167"})
    void aSkillTheConnectorCouldNotReadIsInformationalAdvisoryAndBlockingUnderEnforcement() {
        SnapshotUnderVetting oversize = snapshotOfBytes(PATH, null);
        assertThat(advisory().vet(oversize).findings()).singleElement().satisfies(finding -> {
            assertThat(finding.id()).isEqualTo("skill-not-scanned");
            assertThat(finding.severity()).isEqualTo(Severity.INFO);
            assertThat(finding.message()).contains("scan size limit");
        });
        assertThat(advisory().vet(oversize).state()).isEqualTo(VerdictState.PASS);
        assertThat(advisory().vet(oversize).summary()).contains("1 unread as oversize or non-UTF-8");

        // Under enforcement "we could not check" must not read as "it conformed".
        assertThat(enforcing().vet(oversize).state()).isEqualTo(VerdictState.FAIL);

        SnapshotUnderVetting binary = snapshotOfBytes(PATH, new byte[] {(byte) 0xC3, (byte) 0x28});
        assertThat(advisory().vet(binary).findings()).singleElement().satisfies(finding -> {
            assertThat(finding.id()).isEqualTo("skill-not-scanned");
            assertThat(finding.message()).contains("not valid UTF-8");
        });
    }

    // --- Adversarial frontmatter ------------------------------------------------------------------

    @Test
    @SVCs({"SVC_GW_0167"})
    void anAliasBombIsRefusedByTheLoaderRatherThanExpanded() {
        StringBuilder bomb = new StringBuilder("---\nl0: &l0 [x, x, x, x, x, x, x, x, x, x]\n");
        for (int level = 1; level <= 8; level++) {
            bomb.append("l%d: &l%d [".formatted(level, level));
            for (int i = 0; i < 10; i++) {
                bomb.append(i == 0 ? "" : ", ").append("*l").append(level - 1);
            }
            bomb.append("]\n");
        }
        bomb.append("---\n");

        Finding finding = assertTimeoutPreemptively(
                Duration.ofSeconds(10), () -> onlyFinding(advisory().vet(snapshotOf(Map.of(PATH, bomb.toString())))));

        assertThat(finding.id()).isEqualTo("skill-frontmatter-malformed");
        // Named, so the refusal is demonstrably the alias limit and not some incidental parse error.
        assertThat(finding.message()).containsIgnoringCase("aliases");
    }

    @Test
    @SVCs({"SVC_GW_0167"})
    void deeplyNestedFrontmatterIsRefusedByTheLoader() {
        String nested = "---\ndeep: %s%s\n---\n".formatted("[".repeat(200), "]".repeat(200));

        Finding finding = assertTimeoutPreemptively(
                Duration.ofSeconds(10), () -> onlyFinding(advisory().vet(snapshotOf(Map.of(PATH, nested)))));

        assertThat(finding.id()).isEqualTo("skill-frontmatter-malformed");
        assertThat(finding.message()).containsIgnoringCase("nesting");
    }

    @Test
    @SVCs({"SVC_GW_0167"})
    void anOversizeFrontmatterBlockIsRefusedBeforeItIsParsed() {
        String huge = "---\ndescription: %s\n---\n".formatted("a".repeat(2 * 1024 * 1024));

        Finding finding = assertTimeoutPreemptively(
                Duration.ofSeconds(10), () -> onlyFinding(advisory().vet(snapshotOf(Map.of(PATH, huge)))));

        assertThat(finding.id()).isEqualTo("skill-frontmatter-malformed");
        assertThat(finding.message()).containsIgnoringCase("exceeds the limit");
    }

    @Test
    @SVCs({"SVC_GW_0167"})
    void malformedYamlIsAFindingAndNeverAnExceptionOutOfTheConnector() {
        assertThat(onlyFinding(advisory().vet(snapshotOf(Map.of(PATH, "---\nname: [unclosed\n---\n")))))
                .satisfies(finding -> {
                    assertThat(finding.id()).isEqualTo("skill-frontmatter-malformed");
                    assertThat(finding.location()).isEqualTo(PATH);
                });
        assertThat(onlyFinding(advisory().vet(snapshotOf(Map.of(PATH, "---\nname: hello\n# never closed\n")))))
                .satisfies(finding -> assertThat(finding.message()).contains("never closed"));
        assertThat(onlyFinding(advisory().vet(snapshotOf(Map.of(PATH, "---\n- a\n- b\n---\n")))))
                .satisfies(finding -> assertThat(finding.message()).contains("not a YAML mapping"));
    }

    @Test
    @SVCs({"SVC_GW_0167"})
    void oneDefectiveSkillDoesNotStopTheOthersBeingExamined() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(PATH, CONFORMANT);
        files.put("plugins/hello/skills/broken/SKILL.md", "no frontmatter here");
        files.put("plugins/other/skills/greet/SKILL.md", "---\nname: greet\ndescription: Greets.\n---\n");

        Verdict verdict = advisory().vet(snapshotOf(files));

        assertThat(verdict.findings()).singleElement().satisfies(finding -> assertThat(finding.location())
                .isEqualTo("plugins/hello/skills/broken/SKILL.md"));
        assertThat(verdict.summary()).contains("scanned 3 SKILL.md file(s)");
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private static List<Finding> findings(String skillMarkdown) {
        return advisory().vet(snapshotOf(Map.of(PATH, skillMarkdown))).findings();
    }

    private static List<String> rules(String skillMarkdown) {
        return findings(skillMarkdown).stream().map(Finding::id).toList();
    }

    private static List<String> messages(String skillMarkdown) {
        return findings(skillMarkdown).stream().map(Finding::message).toList();
    }

    private static Finding onlyFinding(Verdict verdict) {
        assertThat(verdict.findings()).hasSize(1);
        return verdict.findings().getFirst();
    }

    private static SkillConformanceConnector advisory() {
        return new SkillConformanceConnector(properties(false));
    }

    private static SkillConformanceConnector enforcing() {
        return new SkillConformanceConnector(properties(true));
    }

    private static SkillsGatewayProperties properties(boolean enforce) {
        SkillsGatewayProperties.Vetting vetting = new SkillsGatewayProperties.Vetting(
                null, null, null, null, null, null, null, null, new SkillsGatewayProperties.Conformance(enforce));
        return new SkillsGatewayProperties(
                null, null, null, null, null, null, vetting, null, null, null, null, null, null, null, null, null,
                null);
    }

    private static SnapshotUnderVetting snapshotOf(Map<String, String> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((path, content) -> bytes.put(path, content.getBytes(StandardCharsets.UTF_8)));
        return snapshot(bytes);
    }

    /** A snapshot of one file whose content may be {@code null} — the walk's "over the size cap" signal. */
    private static SnapshotUnderVetting snapshotOfBytes(String path, byte[] content) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(path, content);
        return snapshot(files);
    }

    private static SnapshotUnderVetting snapshot(Map<String, byte[]> files) {
        return new SnapshotUnderVetting() {

            @Override
            public long snapshotId() {
                return 1;
            }

            @Override
            public String marketplace() {
                return "m";
            }

            @Override
            public String sha() {
                return "0000000000000000000000000000000000000000";
            }

            @Override
            public void walk(Predicate<String> wanted, FileVisitor visitor) {
                files.forEach((path, content) -> {
                    if (wanted.test(path)) {
                        visitor.visit(path, content);
                    }
                });
            }
        };
    }
}

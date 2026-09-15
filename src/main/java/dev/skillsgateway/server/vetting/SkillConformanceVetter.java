package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.policy.SkillFrontmatter;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.stereotype.Component;

/**
 * Built-in connector: SKILL.md conformance against a pinned Agent Skills specification (GW_INGEST_0028).
 *
 * <p>The rest of the chain asks whether content is dangerous. This asks whether a skill is well
 * formed — whether its frontmatter carries the fields an agent needs to load and select it, in the
 * shapes the specification defines. The answer is the one kind of objection a reviewer can act on
 * without judgement: a named missing field is a fact its publisher can fix, where a heuristic
 * scanner's objection is a probability.
 *
 * <p>The specification is {@link SkillSpec vendored and dated}, never fetched — a chain run must be
 * reproducible from the repository alone — and its pin is recorded as this connector's
 * {@link #version()}, so a snapshot flagged today that cleared last month is attributable to the
 * specification bump rather than guessed at (GW_VETTING_0012).
 *
 * <p><b>Posture.</b> Advisory by default: every defect is a {@code MEDIUM} finding, which warns
 * and blocks nothing. A verdict covers the whole snapshot, so a blocking default would let one
 * malformed skill hold up every other skill beside it, and a formatting defect is not the kind of
 * thing the gateway's blocking states are for. An organisation that has decided conformance is a
 * publishing requirement sets {@code skills-gateway.vetting.conformance.enforce}, under which the
 * same defects are {@code HIGH} and waivable like any other blocking finding.
 *
 * <p><b>What it cannot do.</b> It checks presence, type and shape. Whether a description actually
 * says what the skill does and when to use it — the thing that decides if an agent ever selects the
 * skill — is a judgement no deterministic rule makes, and this connector does not pretend to.
 */
@Component
@ImportRuntimeHints(SkillConformanceConnector.SpecResourceHints.class)
public class SkillConformanceConnector implements VettingConnector {

    private static final String SKILL_FILE = "/SKILL.md";
    private static final String SKILLS_DIRECTORY = "skills";

    private final SkillSpec spec;
    private final boolean enforce;

    public SkillConformanceConnector(SkillsGatewayProperties properties) {
        this.spec = SkillSpec.load();
        this.enforce = properties.vetting().conformance().enforce();
    }

    @Override
    public String name() {
        return "skill-conformance";
    }

    @Override
    public int order() {
        return 400;
    }

    /**
     * The pinned specification, a digest of its constraint table, and the posture in force. All
     * three can change the answer about unchanged content, so all three are part of the chain
     * identity a run records (GW_VETTING_0012).
     */
    @Override
    @Requirements({"GW_INGEST_0028"})
    public String version() {
        return spec.version() + "+" + (enforce ? "enforce" : "advisory");
    }

    @Override
    public String description() {
        return "Validates every skill's SKILL.md frontmatter against the vendored " + spec.pin()
                + " Agent Skills specification: required fields, their constraints, and the name/directory"
                + " match. Structure only — whether a description is any good is not a check a rule can make."
                + (enforce ? " Enforcing: defects block." : " Advisory: defects warn, they do not block.");
    }

    @Override
    @Requirements({"GW_INGEST_0028", "GW_VETTING_0023"})
    public Verdict vet(SnapshotUnderVetting snapshot) {
        List<Finding> findings = new ArrayList<>();
        int[] counts = new int[2]; // {examined, unread}
        try {
            snapshot.walk(SkillConformanceConnector::skillDefinition, (path, content) -> {
                String text = ContentRules.text(content);
                if (text == null) {
                    counts[1]++;
                    findings.add(new Finding(
                            "skill-not-scanned",
                            coverageSeverity(),
                            path,
                            content == null
                                    ? "SKILL.md exceeds the configured scan size limit and was not read,"
                                            + " so its conformance could not be checked"
                                    : "SKILL.md is not valid UTF-8 and was not read, so its conformance"
                                            + " could not be checked"));
                    return;
                }
                counts[0]++;
                for (SkillSpec.Defect defect : spec.validate(directoryOf(path), SkillFrontmatter.parse(text))) {
                    findings.add(new Finding(defect.ruleId(), severityOf(defect), path, defect.message()));
                }
            });
        } catch (IOException e) {
            // Reading the snapshot failed midway; the chain records this as an error, which blocks.
            throw new IllegalStateException("cannot read snapshot content", e);
        }
        return Verdict.of(findings, summary(counts[0], counts[1]));
    }

    /**
     * A defect's severity is the posture, with one exception: a frontmatter key the pinned
     * specification does not define never rises above informational. The pin is a snapshot of a
     * moving document, and {@code metadata} exists precisely so clients can carry properties the
     * specification has no opinion about — so blocking on an unrecognised key would turn next
     * month's field into this month's blocked marketplace.
     */
    private Severity severityOf(SkillSpec.Defect defect) {
        return SkillSpec.FIELD_UNKNOWN.equals(defect.ruleId()) ? Severity.INFO : conformanceSeverity();
    }

    private Severity conformanceSeverity() {
        return enforce ? Severity.HIGH : Severity.MEDIUM;
    }

    /**
     * A SKILL.md the connector could not read. Informational under the default posture, matching
     * the {@code file-not-scanned} convention the other connectors use; blocking under enforcement,
     * where "we could not check" must not read as "it conformed".
     */
    private Severity coverageSeverity() {
        return enforce ? Severity.HIGH : Severity.INFO;
    }

    /** What the connector examined (GW_VETTING_0023), recorded even for a clean pass and for no skills at all. */
    private String summary(int examined, int unread) {
        return "scanned %d SKILL.md file(s) under <plugin source>/skills/ against the %s Agent Skills"
                        .formatted(examined, spec.pin())
                + " specification, vendored in this gateway and not fetched%s; posture: %s"
                        .formatted(
                                unread == 0 ? "" : " (%d unread as oversize or non-UTF-8)".formatted(unread),
                                enforce ? "enforce" : "advisory");
    }

    /**
     * The specification's own layout: a skill is a directory under a plugin's {@code skills/}
     * holding a SKILL.md. Declared as the walk's selection so no other blob is opened on this
     * connector's behalf (GW_VETTING_0030). Shape, not manifest: a skill directory the manifest happens
     * not to declare is still content the snapshot ships, and consulting the manifest would let an
     * upstream hide a skill from conformance by omitting its plugin.
     */
    static boolean skillDefinition(String path) {
        if (!path.endsWith(SKILL_FILE)) {
            return false;
        }
        String directory = path.substring(0, path.length() - SKILL_FILE.length());
        int slash = directory.lastIndexOf('/');
        if (slash < 0) {
            return false;
        }
        String parent = directory.substring(0, slash);
        return parent.equals(SKILLS_DIRECTORY) || parent.endsWith('/' + SKILLS_DIRECTORY);
    }

    /** The skill's directory name — the value the specification requires {@code name} to equal. */
    private static String directoryOf(String path) {
        String directory = path.substring(0, path.length() - SKILL_FILE.length());
        return directory.substring(directory.lastIndexOf('/') + 1);
    }

    /** The vendored specification is read from the classpath, so the packaged jar has to keep it. */
    static final class SpecResourceHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.resources().registerPattern(SkillSpec.RESOURCE);
        }
    }
}

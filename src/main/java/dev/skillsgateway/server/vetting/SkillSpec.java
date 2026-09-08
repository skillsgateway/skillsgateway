package dev.skillsgateway.server.vetting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.policy.SkillFrontmatter;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

/**
 * The Agent Skills SKILL.md specification this gateway validates against (GW_INGEST_0028), read from a
 * vendored, dated copy in its own distribution — never from the network. A chain run has to be
 * reproducible from the repository alone, and continuous re-vetting has to be able to say whether
 * a changed answer about unchanged content came from the content or from the rules.
 *
 * <p>Provenance, the departures from the published prose, and how to bump the pin are in
 * {@code src/main/resources/vetting/README.md}. Upstream publishes no version identifier, so
 * {@link #version()} is this repository's dated pin plus a digest of the constraint table: editing
 * the vendored file without moving the date still moves every run's recorded chain identity.
 */
final class SkillSpec {

    static final String RESOURCE = "vetting/agentskills-2026-08-04.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Rule ids, stable because a scoped waiver is written against them. */
    static final String FRONTMATTER_MISSING = "skill-frontmatter-missing";

    static final String FRONTMATTER_MALFORMED = "skill-frontmatter-malformed";
    static final String FIELD_MISSING = "skill-field-missing";
    static final String FIELD_INVALID = "skill-field-invalid";
    static final String FIELD_UNKNOWN = "skill-field-unknown";

    /** One field of the specification's frontmatter table and the constraints on it. */
    record Field(
            String name,
            boolean required,
            String type,
            Integer maxLength,
            boolean lowercase,
            boolean alphanumericOrHyphen,
            boolean noLeadingOrTrailingHyphen,
            boolean noConsecutiveHyphens,
            boolean mustMatchDirectoryName) {}

    /** One departure from the specification, before a severity is attached to it. */
    record Defect(String ruleId, String message) {}

    private final String pin;
    private final List<Field> fields;
    private final Set<String> known;
    private final String digest;

    private SkillSpec(String pin, List<Field> fields) {
        this.pin = pin;
        this.fields = List.copyOf(fields);
        this.known = new LinkedHashSet<>(fields.stream().map(Field::name).toList());
        this.digest = digest(fields);
    }

    static SkillSpec load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = MAPPER.readTree(in);
            List<Field> fields = new ArrayList<>();
            for (JsonNode field : root.path("fields")) {
                fields.add(new Field(
                        field.path("name").asText(),
                        field.path("required").asBoolean(false),
                        field.path("type").asText("string"),
                        field.hasNonNull("maxLength") ? field.get("maxLength").asInt() : null,
                        field.path("lowercase").asBoolean(false),
                        field.path("alphanumericOrHyphen").asBoolean(false),
                        field.path("noLeadingOrTrailingHyphen").asBoolean(false),
                        field.path("noConsecutiveHyphens").asBoolean(false),
                        field.path("mustMatchDirectoryName").asBoolean(false)));
            }
            if (fields.isEmpty()) {
                throw new IllegalStateException("vendored specification %s declares no fields".formatted(RESOURCE));
            }
            return new SkillSpec(root.path("version").asText(), fields);
        } catch (IOException e) {
            // The specification ships inside the jar; missing it is a broken build, not a runtime state.
            throw new IllegalStateException("vendored specification %s is unreadable".formatted(RESOURCE), e);
        }
    }

    /** The dated pin plus a digest of the constraint table, recorded as the connector's version. */
    String version() {
        return pin + "+schema-" + digest;
    }

    /** The dated pin on its own, for reviewer-facing prose. */
    String pin() {
        return pin;
    }

    /**
     * Every way one SKILL.md departs from the specification, in table order and then in document
     * order. A conformant skill yields an empty list; nothing here throws, because a defect is
     * something to report rather than something to refuse.
     */
    @Requirements({"GW_INGEST_0028"})
    List<Defect> validate(String directoryName, SkillFrontmatter.Parsed parsed) {
        if (parsed.malformed()) {
            return List.of(new Defect(FRONTMATTER_MALFORMED, parsed.error()));
        }
        if (parsed.absent()) {
            return List.of(new Defect(
                    FRONTMATTER_MISSING,
                    "SKILL.md does not open with a YAML frontmatter block, so it declares neither"
                            + " a name nor a description"));
        }
        Map<String, Object> declared = parsed.fields();
        List<Defect> defects = new ArrayList<>();
        for (Field field : fields) {
            Object value = declared.get(field.name());
            if (value == null) {
                if (field.required()) {
                    defects.add(new Defect(
                            FIELD_MISSING,
                            "required frontmatter field '%s' is missing or empty: %s"
                                    .formatted(field.name(), expectation(field))));
                }
                continue;
            }
            defects.addAll(check(field, value, directoryName));
        }
        for (String key : declared.keySet()) {
            if (!known.contains(key)) {
                defects.add(new Defect(
                        FIELD_UNKNOWN,
                        "frontmatter field '%s' is not defined by the %s specification; carry".formatted(key, pin)
                                + " client-specific properties under 'metadata' instead"));
            }
        }
        return List.copyOf(defects);
    }

    private List<Defect> check(Field field, Object value, String directoryName) {
        List<Defect> defects = new ArrayList<>();
        switch (field.type()) {
            case "map" -> {
                if (!(value instanceof Map<?, ?> mapping)) {
                    defects.add(invalid(field, "must be a mapping of string keys to string values"));
                } else if (!mapping.values().stream().allMatch(SkillSpec::scalar)) {
                    defects.add(invalid(field, "must map string keys to string values, not to nested structures"));
                }
            }
            case "string-or-list" -> {
                boolean scalars = value instanceof List<?> list
                        ? list.stream().allMatch(SkillSpec::scalar)
                        : value instanceof String;
                if (!scalars) {
                    defects.add(invalid(field, "must be a string or a list of strings"));
                }
            }
            default -> defects.addAll(checkString(field, value, directoryName));
        }
        return defects;
    }

    private List<Defect> checkString(Field field, Object value, String directoryName) {
        if (!(value instanceof String text)) {
            return List.of(invalid(field, "must be a string, not " + kind(value)));
        }
        String normalized = Normalizer.normalize(text.strip(), Normalizer.Form.NFKC);
        if (normalized.isEmpty()) {
            return List.of(invalid(field, "must be a non-empty string"));
        }
        List<Defect> defects = new ArrayList<>();
        if (field.maxLength() != null && normalized.length() > field.maxLength()) {
            defects.add(invalid(
                    field,
                    "is %d characters, over the %d character limit".formatted(normalized.length(), field.maxLength())));
        }
        if (field.lowercase() && !normalized.equals(normalized.toLowerCase(Locale.ROOT))) {
            defects.add(invalid(field, "must be lowercase"));
        }
        if (field.alphanumericOrHyphen()
                && !normalized.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-')) {
            defects.add(invalid(field, "may contain only letters, digits and hyphens"));
        }
        if (field.noLeadingOrTrailingHyphen() && (normalized.startsWith("-") || normalized.endsWith("-"))) {
            defects.add(invalid(field, "must not start or end with a hyphen"));
        }
        if (field.noConsecutiveHyphens() && normalized.contains("--")) {
            defects.add(invalid(field, "must not contain consecutive hyphens"));
        }
        if (field.mustMatchDirectoryName()
                && !normalized.equals(Normalizer.normalize(directoryName, Normalizer.Form.NFKC))) {
            defects.add(invalid(
                    field, "is '%s' but the skill directory is '%s'; they must match".formatted(text, directoryName)));
        }
        return defects;
    }

    private static Defect invalid(Field field, String problem) {
        return new Defect(FIELD_INVALID, "frontmatter field '%s' %s".formatted(field.name(), problem));
    }

    private static boolean scalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static String kind(Object value) {
        if (value instanceof Map<?, ?>) {
            return "a mapping";
        }
        if (value instanceof List<?>) {
            return "a list";
        }
        return "a " + value.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private static String expectation(Field field) {
        return field.maxLength() == null
                ? "a %s is required".formatted(field.type())
                : "a %s of at most %d characters is required".formatted(field.type(), field.maxLength());
    }

    /**
     * Identity of the constraint table itself, so a hand edit of the vendored file that leaves its
     * date alone still changes what every run records. Over the parsed table rather than the raw
     * bytes: a comment or a reflow is not a rule change.
     */
    private static String digest(List<Field> fields) {
        StringBuilder canonical = new StringBuilder();
        for (Field field : fields) {
            canonical
                    .append(field.name())
                    .append('|')
                    .append(field.required())
                    .append('|')
                    .append(field.type())
                    .append('|')
                    .append(field.maxLength())
                    .append('|')
                    .append(field.lowercase())
                    .append(field.alphanumericOrHyphen())
                    .append(field.noLeadingOrTrailingHyphen())
                    .append(field.noConsecutiveHyphens())
                    .append(field.mustMatchDirectoryName())
                    .append('\n');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 3);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

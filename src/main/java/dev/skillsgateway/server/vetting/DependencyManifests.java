package dev.skillsgateway.server.vetting;

import com.fasterxml.jackson.databind.JsonNode;
import dev.skillsgateway.server.ingestion.PluginComponents;
import io.github.reqstool.annotations.Requirements;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a dependency manifest declares dependencies that are installed at run time
 * (GW_VETTING_0053). Pure over text. TOML and requirement files are read line by line: the only
 * question is whether a section is empty, which does not need a parser.
 */
final class DependencyManifests {

    /** A manifest's first declaring line, and the ecosystem that installs from it. */
    record Declaration(int line, String ecosystem) {}

    private static final List<String> NPM_KEYS =
            List.of("dependencies", "devDependencies", "optionalDependencies", "peerDependencies");

    private static final Map<String, List<String>> LOCKFILES = Map.of(
            "npm",
            List.of("package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "bun.lockb"),
            "Python",
            List.of("uv.lock", "poetry.lock", "pdm.lock", "Pipfile.lock"),
            "Cargo",
            List.of("Cargo.lock"));

    private static final Pattern REQUIREMENTS = Pattern.compile("requirements[\\w.-]*\\.txt");
    private static final Pattern INCLUDE = Pattern.compile("^(?:-[rce]|--(?:requirement|constraint|editable))\\b.*");
    private static final Pattern SECTION = Pattern.compile("^\\[\\[?\\s*([^\\]]+?)\\s*\\]\\]?\\s*(?:#.*)?$");
    private static final Pattern KEY = Pattern.compile("^(\"[^\"]+\"|'[^']+'|[\\w.-]+)\\s*=.*");
    private static final Pattern ARRAY = Pattern.compile("^([\\w.-]+)\\s*=\\s*\\[(.*)$");
    private static final Pattern POETRY = Pattern.compile("^tool\\.poetry(?:\\.group\\.[^.]+)?\\.dependencies$");
    private static final Pattern CARGO_TABLE = Pattern.compile("^(?:.+\\.)?(?:dev-|build-)?dependencies$");
    private static final Pattern CARGO_ONE = Pattern.compile("^(?:.+\\.)?(?:dev-|build-)?dependencies\\.[^.]+$");

    private DependencyManifests() {}

    static boolean isManifest(String path) {
        String name = name(path);
        return name.equals("package.json")
                || name.equals("pyproject.toml")
                || name.equals("Cargo.toml")
                || REQUIREMENTS.matcher(name).matches();
    }

    /** The first dependency the manifest declares, or {@code null} when it declares none or cannot be read. */
    @Requirements({"GW_VETTING_0053"})
    static Declaration declaration(String path, String text) {
        String name = name(path);
        String[] lines = text.split("\\r?\\n", -1);
        return switch (name) {
            case "package.json" -> npm(path, text);
            case "pyproject.toml" -> pyproject(lines);
            case "Cargo.toml" -> cargo(lines);
            default -> requirements(lines);
        };
    }

    /** The lockfile beside the manifest, or {@code null}. */
    static String lockfile(String path, String ecosystem, Collection<String> paths) {
        String directory = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        for (String candidate : LOCKFILES.get(ecosystem)) {
            if (paths.contains(directory + candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Declaration npm(String path, String text) {
        PluginComponents.Manifest manifest;
        try {
            manifest = PluginComponents.Manifest.parse(path, text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Not valid JSON: npm refuses it, so nothing is installed from it.
            return null;
        }
        int first = Integer.MAX_VALUE;
        for (String key : NPM_KEYS) {
            JsonNode value = manifest.root().path(key);
            if (value.isObject() && !value.isEmpty()) {
                first = Math.min(first, manifest.lines().getOrDefault("/" + key, 1));
            }
        }
        return first == Integer.MAX_VALUE ? null : new Declaration(first, "npm");
    }

    private static Declaration requirements(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!line.startsWith("-") || INCLUDE.matcher(line).matches()) {
                return new Declaration(i + 1, "Python");
            }
        }
        return null;
    }

    private static Declaration pyproject(String[] lines) {
        String section = null;
        int sectionLine = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            Matcher header = SECTION.matcher(line);
            if (header.matches()) {
                section = header.group(1);
                sectionLine = i + 1;
                continue;
            }
            if (section == null || line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher array = ARRAY.matcher(line);
            boolean projectDependencies = section.equals("project")
                    && array.matches()
                    && array.group(1).equals("dependencies");
            boolean group = section.equals("dependency-groups") && array.matches();
            if ((projectDependencies || group) && nonEmptyArray(array.group(2), lines, i)) {
                return new Declaration(i + 1, "Python");
            }
            Matcher key = KEY.matcher(line);
            if (POETRY.matcher(section).matches()
                    && key.matches()
                    && !unquote(key.group(1)).equals("python")) {
                return new Declaration(sectionLine, "Python");
            }
        }
        return null;
    }

    private static Declaration cargo(String[] lines) {
        String section = null;
        int sectionLine = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            Matcher header = SECTION.matcher(line);
            if (header.matches()) {
                section = header.group(1);
                sectionLine = i + 1;
                if (CARGO_ONE.matcher(section).matches()) {
                    return new Declaration(sectionLine, "Cargo");
                }
                continue;
            }
            if (section != null
                    && CARGO_TABLE.matcher(section).matches()
                    && KEY.matcher(line).matches()) {
                return new Declaration(sectionLine, "Cargo");
            }
        }
        return null;
    }

    /** Whether an array whose text after {@code [} is {@code rest}, on line {@code at}, has an element. */
    private static boolean nonEmptyArray(String rest, String[] lines, int at) {
        String inline = rest.replaceFirst("#.*$", "").strip();
        if (!inline.isEmpty()) {
            return !inline.startsWith("]");
        }
        for (int j = at + 1; j < lines.length; j++) {
            String next = lines[j].strip();
            if (next.isEmpty() || next.startsWith("#")) {
                continue;
            }
            return !next.startsWith("]");
        }
        return false;
    }

    private static String unquote(String key) {
        return key.length() > 1 && (key.startsWith("\"") || key.startsWith("'"))
                ? key.substring(1, key.length() - 1)
                : key;
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}

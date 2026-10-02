package dev.skillsgateway.server.ingestion;

import dev.skillsgateway.server.ingestion.PluginComponents.HookModule;
import dev.skillsgateway.server.ingestion.PluginComponents.Problem;
import dev.skillsgateway.server.ingestion.PluginComponents.Site;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lexical reading of a Claude Code hook module (GW_INGEST_0065): the events its {@code on(...)}
 * calls register, the engine interfaces it reaches as {@code $.<name>}, and the plugin files it
 * imports, followed transitively. Comments are blanked first; string contents are kept.
 *
 * <p>It matches shapes, not a syntax tree: a renamed {@code $}, an interface held in a variable or
 * an event name built at run time walks past it. What it cannot read is returned as unscanned.
 */
final class HookModules {

    static final int MAX_FILES = 50;

    /** The suffixes Claude Code loads a module or an imported file under. */
    private static final List<String> SUFFIXES = List.of(".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs");

    private static final Pattern EVENT = Pattern.compile("(?<![\\w.$])on\\(\\s*(['\"`])([\\w.:-]+)\\1");
    private static final Pattern USE = Pattern.compile("(?<![\\w$])\\$\\.([A-Za-z_]\\w*)");
    private static final Pattern IMPORT_FROM =
            Pattern.compile("\\b(?:import|export)\\s[^;'\"]*?\\bfrom\\s*(['\"])([^'\"\\n]+)\\1");
    private static final Pattern IMPORT_BARE = Pattern.compile("\\bimport\\s*(['\"])([^'\"\\n]+)\\1");

    private HookModules() {}

    @Requirements({"GW_INGEST_0065"})
    static HookModule read(PluginComponents.Files files, String root, String path, String location) {
        Map<String, Site> events = new LinkedHashMap<>();
        Map<String, Site> uses = new LinkedHashMap<>();
        List<String> followed = new ArrayList<>();
        List<Problem> unscanned = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (!files.paths().contains(path)) {
            unscanned.add(new Problem(path, "not in the snapshot, so no rule read it"));
        } else {
            queue.add(path);
            visited.add(path);
        }
        while (!queue.isEmpty()) {
            if (followed.size() >= MAX_FILES) {
                unscanned.add(new Problem(
                        path, "imports more than %d files; the rest were not followed".formatted(MAX_FILES)));
                break;
            }
            String file = queue.poll();
            String text = text(files, file, unscanned);
            if (text == null) {
                continue;
            }
            followed.add(file);
            String code = blankComments(text);
            collect(EVENT, 2, code, file, events);
            collect(USE, 1, code, file, uses);
            // The raw text too: a commented or oddly hidden import is followed, never missed.
            for (Import imported : imports(code, text)) {
                String target = target(files, root, file, imported, unscanned);
                if (target != null && visited.add(target)) {
                    queue.add(target);
                }
            }
        }
        return new HookModule(
                path,
                location,
                List.copyOf(events.values()),
                List.copyOf(uses.values()),
                List.copyOf(followed),
                List.copyOf(unscanned));
    }

    private record Import(String specifier, int line) {}

    /** The file's text, or {@code null} with the reason added to {@code unscanned}. */
    private static String text(PluginComponents.Files files, String file, List<Problem> unscanned) {
        byte[] content;
        try {
            content = files.read(file);
        } catch (IOException e) {
            content = null;
        }
        if (content == null) {
            unscanned.add(new Problem(file, "over the scan size limit, so no rule read it"));
            return null;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            unscanned.add(new Problem(file, "binary, so no rule can read it"));
            return null;
        }
    }

    /** First appearance of each name, located in the file it appears in. */
    private static void collect(Pattern pattern, int group, String code, String file, Map<String, Site> out) {
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            String name = matcher.group(group);
            out.putIfAbsent(name, new Site(name, file + ":" + lineAt(code, matcher.start())));
        }
    }

    private static List<Import> imports(String... texts) {
        Map<String, Import> found = new LinkedHashMap<>();
        for (String text : texts) {
            for (Pattern pattern : List.of(IMPORT_FROM, IMPORT_BARE)) {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    Import imported = new Import(matcher.group(2), lineAt(text, matcher.start(2)));
                    found.putIfAbsent(imported.specifier() + "\0" + imported.line(), imported);
                }
            }
        }
        List<Import> sorted = new ArrayList<>(found.values());
        sorted.sort((a, b) -> Integer.compare(a.line(), b.line()));
        return sorted;
    }

    /**
     * The plugin file an import names, or {@code null}: {@code claude-code} is types only, and a
     * package, a path outside the plugin or a missing file is added to {@code unscanned}.
     */
    private static String target(
            PluginComponents.Files files, String root, String file, Import imported, List<Problem> unscanned) {
        String specifier = imported.specifier();
        String at = file + ":" + imported.line();
        if (specifier.equals("claude-code") || specifier.startsWith("claude-code/")) {
            return null;
        }
        if (!specifier.startsWith("./") && !specifier.startsWith("../")) {
            unscanned.add(new Problem(
                    at,
                    "imports the package '%s', which is outside the plugin, so no rule read it".formatted(specifier)));
            return null;
        }
        String directory = file.contains("/") ? file.substring(0, file.lastIndexOf('/')) : "";
        String base = PluginComponents.resolve("", PluginComponents.join(directory, specifier));
        if (base == null || !(root.isEmpty() || base.startsWith(root + "/"))) {
            unscanned.add(new Problem(
                    at, "imports '%s', which is outside the plugin, so no rule read it".formatted(specifier)));
            return null;
        }
        for (String candidate : candidates(base)) {
            if (files.paths().contains(candidate)) {
                return candidate;
            }
        }
        unscanned.add(
                new Problem(at, "imports '%s', which is not in the snapshot, so no rule read it".formatted(specifier)));
        return null;
    }

    /** The exact path, then each suffix, then an index file; a {@code .js} name may stand for its TypeScript source. */
    private static List<String> candidates(String base) {
        List<String> candidates = new ArrayList<>();
        candidates.add(base);
        SUFFIXES.forEach(suffix -> candidates.add(base + suffix));
        SUFFIXES.forEach(suffix -> candidates.add(base + "/index" + suffix));
        Matcher js = Pattern.compile("\\.(m|c)?js$").matcher(base);
        if (js.find()) {
            String stem = base.substring(0, js.start());
            String flavour = js.group(1) == null ? "" : js.group(1);
            candidates.add(stem + "." + flavour + "ts");
            if (flavour.isEmpty()) {
                candidates.add(stem + ".tsx");
            }
        }
        return candidates;
    }

    /**
     * {@code text} with every comment replaced by spaces, newlines kept so lines still count; the
     * contents of string and template literals are kept, so {@code 'https://…'} is not a comment.
     */
    static String blankComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = text.indexOf("*/", i + 2);
                int stop = end < 0 ? text.length() : end + 2;
                for (; i < stop; i++) {
                    out.append(text.charAt(i) == '\n' ? '\n' : ' ');
                }
            } else if (c == '/' && regexMayStart(out)) {
                // A regex literal: its contents are kept, so a '//' inside it is not a comment.
                boolean inClass = false;
                out.append(c);
                i++;
                while (i < text.length() && text.charAt(i) != '\n' && (inClass || text.charAt(i) != '/')) {
                    char r = text.charAt(i);
                    if (r == '\\' && i + 1 < text.length()) {
                        out.append(text.charAt(i++));
                    } else if (r == '[') {
                        inClass = true;
                    } else if (r == ']') {
                        inClass = false;
                    }
                    out.append(text.charAt(i++));
                }
                if (i < text.length() && text.charAt(i) == '/') {
                    out.append(text.charAt(i++));
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                out.append(c);
                i++;
                while (i < text.length() && text.charAt(i) != c && (c == '`' || text.charAt(i) != '\n')) {
                    if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                        out.append(text.charAt(i++));
                    }
                    out.append(text.charAt(i++));
                }
                if (i < text.length()) {
                    out.append(text.charAt(i++));
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** A {@code /} starts a regex literal, not a division, after an operator, an opening bracket or nothing. */
    private static boolean regexMayStart(StringBuilder before) {
        int i = before.length() - 1;
        while (i >= 0 && Character.isWhitespace(before.charAt(i))) {
            i--;
        }
        return i < 0 || "(,=:[!&|?{};+-*%<>~^".indexOf(before.charAt(i)) >= 0;
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}

package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.ingestion.PluginComponents;
import dev.skillsgateway.server.ingestion.PluginComponents.Hook;
import dev.skillsgateway.server.ingestion.PluginComponents.McpCommand;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Built-in vetter: the code a plugin runs without anyone invoking it (GW_VETTING_0047 -
 * GW_VETTING_0049).
 *
 * <p>Every hook is a medium finding naming its trigger: it runs on session start, after an edit,
 * at the end of a turn, and the approval is also an approval of that. Where a hook's command, or a
 * file of the snapshot it launches (followed to a depth of {@value #MAX_DEPTH}), downloads code
 * and executes it, the finding is high: that code was never in the snapshot the gateway pinned,
 * so it bypasses quarantine entirely.
 *
 * <p>The plugin layout is read by {@link PluginComponents}, the same reading the inventory uses.
 * Roots are the manifest's plugin sources plus every directory holding a
 * {@code .claude-plugin/plugin.json}: shape, not manifest, as {@link SkillConformanceVetter} does,
 * so a plugin cannot keep its hooks out of view by being left out of the manifest.
 *
 * <p>Every local MCP server's command is scanned with the same rules under its own ids
 * (GW_VETTING_0050 - GW_VETTING_0052): a package runner warns, because nearly every published
 * server starts that way, and download-and-execute blocks, as in a hook. An LSP server's command
 * is scanned the same way under ids of its own (GW_VETTING_0055).
 *
 * <p>Code installed after the snapshot was pinned is flagged outside hooks and servers too
 * (GW_VETTING_0053, GW_VETTING_0054, GW_VETTING_0056): a dependency manifest, and an install in a
 * skill's scripts or in a fenced block of a skill, command or agent, warn; download-and-execute
 * there blocks.
 *
 * <p><b>What it cannot do.</b> It matches shapes. A fetch reached through variable indirection or
 * an encoded payload walks past it, and monitor commands are listed in the inventory but not
 * examined here.
 */
@Component
public class ExecutableSurfaceVetter implements Vetter {

    static final int MAX_DEPTH = 3;

    private static final String MANIFEST_PATH = ".claude-plugin/marketplace.json";
    private static final String PLUGIN_JSON = ".claude-plugin/plugin.json";
    private static final int SHOWN_COMMAND = 200;

    /** Path-shaped tokens: split on whitespace, quotes and shell punctuation. */
    private static final Pattern TOKEN = Pattern.compile("[^\\s\"'`;|&<>()=,]+");

    private static final Pattern PATH = Pattern.compile("[\\w.@+-]+(?:/[\\w.@+-]+)*");
    private static final Pattern PLUGIN_ROOT = Pattern.compile("^\\$(?:\\{CLAUDE_PLUGIN_ROOT}|CLAUDE_PLUGIN_ROOT)/");
    private static final Pattern VARIABLE_PREFIX = Pattern.compile("^\\$(?:\\{\\w+}|\\w+)/");
    private static final Pattern SCRIPT =
            Pattern.compile(".*\\.(?:sh|bash|zsh|py|js|mjs|cjs|ts|ps1|cmd|bat|rb|pl|php)$");

    static final String MCP_FETCH_EXEC = "mcp-fetch-exec";
    static final String MCP_PACKAGE_RUN = "mcp-package-run";
    static final String LSP_FETCH_EXEC = "lsp-fetch-exec";
    static final String LSP_PACKAGE_RUN = "lsp-package-run";
    static final String RUNTIME_DEPENDENCY = "runtime-dependency";
    static final String SKILL_FETCH_EXEC = "skill-fetch-exec";

    /** Installed or built trees: vendored content, not a declaration of what to install. */
    private static final Set<String> VENDORED = Set.of("node_modules", ".venv", "venv", "site-packages", "target");

    /** A kind of server Claude Code starts as a local process, and the rule ids its findings carry. */
    private enum Server {
        MCP("MCP server", MCP_FETCH_EXEC, MCP_PACKAGE_RUN),
        LSP("LSP server", LSP_FETCH_EXEC, LSP_PACKAGE_RUN);

        private final String label;
        private final String fetchExec;
        private final String packageRun;

        Server(String label, String fetchExec, String packageRun) {
            this.label = label;
            this.fetchExec = fetchExec;
            this.packageRun = packageRun;
        }
    }

    /** {@code ${VAR:-default}}: what runs when the variable is unset, which is what is approved. */
    private static final Pattern VARIABLE_DEFAULT = Pattern.compile("\\$\\{\\w+:-([^}]*)}");

    private static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "dash", "ksh");
    private static final Set<String> RUNNERS = Set.of("npx", "bunx", "pnpx", "uvx");

    @Override
    public String name() {
        return "executable-surface";
    }

    @Override
    public int order() {
        return 250;
    }

    @Override
    public String description() {
        return "Lists every hook a plugin declares with its trigger, and blocks where a hook — or a script it"
                + " launches — downloads code and executes it, or runs a package fetched at run time. Scans local"
                + " MCP and LSP server commands too: a package runner warns, download-and-execute blocks. Warns on"
                + " dependency manifests and on installs in skill scripts and in fenced blocks of skills, commands"
                + " and agents, where download-and-execute blocks. Shape-based: indirection and encoding walk past"
                + " it, and monitor commands are not examined.";
    }

    @Override
    @Requirements({
        "GW_VETTING_0047",
        "GW_VETTING_0048",
        "GW_VETTING_0049",
        "GW_VETTING_0023",
        "GW_VETTING_0050",
        "GW_VETTING_0051",
        "GW_VETTING_0052",
        "GW_VETTING_0053",
        "GW_VETTING_0054",
        "GW_VETTING_0055",
        "GW_VETTING_0056"
    })
    public Verdict vet(SnapshotUnderVetting snapshot) {
        try {
            return new Run(SnapshotFiles.of(snapshot)).vet();
        } catch (IOException e) {
            // Reading the snapshot failed midway; the chain records this as an error, which blocks.
            throw new IllegalStateException("cannot read snapshot content", e);
        }
    }

    /** One chain run's state: the findings so far, de-duplicated on rule, location and message. */
    private static final class Run {

        private final SnapshotFiles files;
        private final Map<String, Finding> findings = new LinkedHashMap<>();
        private final Set<String> scanned = new HashSet<>();
        // Apart from the hooks' set, so a server's medium scan never hides a hook's high one.
        private final Map<Server, Set<String>> serverScanned =
                Map.of(Server.MCP, new HashSet<>(), Server.LSP, new HashSet<>());
        private int hooks;
        private int servers;
        private int lspServers;
        private int manifestsRead;
        private int skillFilesRead;

        Run(SnapshotFiles files) {
            this.files = files;
        }

        Verdict vet() throws IOException {
            Map<String, String> roots = new LinkedHashMap<>();
            PluginComponents.Manifest manifest = manifest(roots);
            for (String path : files.paths()) {
                if (path.equals(PLUGIN_JSON) || path.endsWith("/" + PLUGIN_JSON)) {
                    roots.putIfAbsent(
                            path.substring(0, path.length() - PLUGIN_JSON.length())
                                    .replaceAll("/$", ""),
                            null);
                }
            }
            Set<String> skillDirectories = new LinkedHashSet<>();
            Set<String> instructions = new LinkedHashSet<>();
            for (Map.Entry<String, String> root : roots.entrySet()) {
                PluginComponents.Components components = PluginComponents.read(
                        files, root.getKey(), root.getValue() == null ? null : manifest, root.getValue());
                components
                        .skillFiles()
                        .forEach(skill -> skillDirectories.add(skill.substring(0, skill.lastIndexOf('/'))));
                for (PluginComponents.Component component : components.commands()) {
                    instructions.add(component.path());
                }
                for (PluginComponents.Component component : components.agents()) {
                    instructions.add(component.path());
                }
                for (PluginComponents.Problem problem : components.hookProblems()) {
                    add(new Finding("hook-config-unreadable", Severity.MEDIUM, problem.path(), problem.message()));
                }
                for (Hook hook : components.hooks()) {
                    hook(root.getKey(), hook);
                }
                for (McpCommand server : components.mcpCommands()) {
                    servers++;
                    server(root.getKey(), server, Server.MCP);
                }
                for (McpCommand server : components.lspCommands()) {
                    lspServers++;
                    server(root.getKey(), server, Server.LSP);
                }
            }
            Set<String> launched = new HashSet<>(scanned);
            serverScanned.values().forEach(launched::addAll);
            installed(roots.keySet(), skillDirectories, instructions, launched);
            return Verdict.of(
                    List.copyOf(findings.values()),
                    ("examined %d plugin root(s): %d hook(s), %d MCP server command(s), %d LSP server command(s),"
                                    + " %d launched file(s) scanned for runtime fetches; %d dependency manifest(s) and %d"
                                    + " skill, command or agent file(s) read; monitor commands are not examined")
                            .formatted(
                                    roots.size(),
                                    hooks,
                                    servers,
                                    lspServers,
                                    launched.size(),
                                    manifestsRead,
                                    skillFilesRead));
        }

        /** The manifest's plugin roots into {@code roots}, keyed to their entry's JSON pointer. */
        private PluginComponents.Manifest manifest(Map<String, String> roots) throws IOException {
            if (!files.paths().contains(MANIFEST_PATH)) {
                return null;
            }
            byte[] content = files.read(MANIFEST_PATH);
            PluginComponents.Manifest manifest;
            try {
                manifest = content == null ? null : PluginComponents.Manifest.parse(MANIFEST_PATH, content);
            } catch (IOException | RuntimeException e) {
                manifest = null;
            }
            if (manifest == null) {
                add(new Finding(
                        "hook-config-unreadable",
                        Severity.MEDIUM,
                        MANIFEST_PATH,
                        "the marketplace manifest could not be read, so hooks its entries declare could not be read"));
                return null;
            }
            var entries = manifest.root().path("plugins");
            for (int i = 0; i < entries.size(); i++) {
                var source = entries.get(i).path("source");
                String root = source.isTextual() ? PluginComponents.normalizeRoot(source.asText()) : null;
                if (root != null) {
                    roots.putIfAbsent(root, "/plugins/" + i);
                }
            }
            return manifest;
        }

        @Requirements({"GW_VETTING_0047"})
        private void hook(String root, Hook hook) throws IOException {
            hooks++;
            add(new Finding("auto-run-hook", Severity.MEDIUM, hook.location(), describe(hook)));
            if (!"command".equals(hook.type()) || hook.runs() == null) {
                return;
            }
            for (RuntimeFetch.Match match : RuntimeFetch.scan(hook.runs(), false)) {
                add(new Finding(match.rule(), Severity.HIGH, hook.location(), "this hook " + match.message()));
            }
            follow(hook.runs(), root, hook.location(), null);
        }

        /**
         * A local MCP or LSP server's command: package runners warn and download-and-execute blocks,
         * under ids of that kind of server, and the files of the plugin it names are followed as a
         * hook's are.
         */
        @Requirements({"GW_VETTING_0050", "GW_VETTING_0051", "GW_VETTING_0052", "GW_VETTING_0055"})
        private void server(String root, McpCommand server, Server kind) throws IOException {
            List<String> words = new ArrayList<>();
            words.add(withDefaults(server.command()));
            server.args().forEach(arg -> words.add(withDefaults(arg)));
            dropEnv(words);
            if (words.isEmpty()) {
                return;
            }
            if (words.getFirst().startsWith("/")) {
                words.set(0, words.getFirst().substring(words.getFirst().lastIndexOf('/') + 1));
            }
            String text = String.join(" ", words);
            String script = wrappedScript(words);
            if (script != null) {
                text = text + "\n" + script;
            }
            boolean localRunner = localRunner(words, root);
            Set<String> rules = new HashSet<>();
            for (RuntimeFetch.Match match : RuntimeFetch.scan(text, false)) {
                boolean runner = RuntimeFetch.PACKAGE_RUN.equals(match.rule());
                if (runner && localRunner && match.line() == 1) {
                    continue;
                }
                if (rules.add(match.rule())) {
                    add(new Finding(
                            runner ? kind.packageRun : kind.fetchExec,
                            runner ? Severity.MEDIUM : Severity.HIGH,
                            server.location(),
                            "%s '%s' %s".formatted(kind.label, server.name(), match.message())));
                }
            }
            follow(text, root, server.location(), kind);
        }

        /**
         * Code installed or fetched after the snapshot was pinned, outside hooks and servers: every
         * dependency manifest of a plugin, and the scripts and fenced code blocks of its skills,
         * commands and agents. A file already scanned as one a hook or a server launches is skipped,
         * so a line carries one finding. Every file is read in one walk of the tree.
         */
        @Requirements({"GW_VETTING_0053", "GW_VETTING_0054", "GW_VETTING_0056"})
        private void installed(
                Collection<String> roots, Set<String> skillDirectories, Set<String> instructions, Set<String> launched)
                throws IOException {
            Set<String> manifests = new LinkedHashSet<>();
            Set<String> skillFiles = new LinkedHashSet<>();
            for (String path : files.paths()) {
                if (vendored(path) || roots.stream().noneMatch(root -> within(path, root))) {
                    continue;
                }
                if (DependencyManifests.isManifest(path)) {
                    manifests.add(path);
                } else if (!launched.contains(path)
                        && (instructions.contains(path) || underAny(path, skillDirectories))) {
                    skillFiles.add(path);
                }
            }
            Set<String> wanted = new LinkedHashSet<>(manifests);
            wanted.addAll(skillFiles);
            Map<String, byte[]> contents = files.readAll(wanted);
            manifestsRead = manifests.size();
            skillFilesRead = skillFiles.size();
            for (String path : manifests) {
                manifest(path, ContentRules.text(contents.get(path)));
            }
            for (String path : skillFiles) {
                skillFile(path, ContentRules.text(contents.get(path)));
            }
        }

        @Requirements({"GW_VETTING_0053"})
        private void manifest(String path, String text) {
            DependencyManifests.Declaration declared =
                    text == null ? null : DependencyManifests.declaration(path, text);
            if (declared == null) {
                return;
            }
            String lockfile = DependencyManifests.lockfile(path, declared.ecosystem(), files.paths());
            add(new Finding(
                    RUNTIME_DEPENDENCY,
                    Severity.MEDIUM,
                    "%s:%d".formatted(path, declared.line()),
                    "declares %s dependencies, which are installed after the snapshot was pinned (%s)"
                            .formatted(
                                    declared.ecosystem(),
                                    lockfile == null ? "no lockfile" : "lockfile present: " + lockfile)));
        }

        /**
         * A skill, command or agent file: at most one finding per rule, at its first matching line,
         * naming every line, so one decision about one file is one group to read and waive.
         */
        @Requirements({"GW_VETTING_0054", "GW_VETTING_0056"})
        private void skillFile(String path, String text) {
            if (text == null) {
                return;
            }
            Map<String, List<Integer>> lines = new LinkedHashMap<>();
            Map<String, String> messages = new HashMap<>();
            String what;
            if (path.endsWith(".md")) {
                what = "a code block of these instructions";
                for (MarkdownFences.Block block : MarkdownFences.of(text)) {
                    for (RuntimeFetch.Match match : RuntimeFetch.scan(block.text(), true)) {
                        collect(lines, messages, block.firstLine() + match.line() - 1, match);
                    }
                }
            } else if (SCRIPT.matcher(path).matches() || text.startsWith("#!")) {
                what = "a skill script";
                for (RuntimeFetch.Match match : RuntimeFetch.scan(text, true)) {
                    collect(lines, messages, match.line(), match);
                }
            } else {
                return;
            }
            lines.forEach((rule, at) -> {
                boolean runner = RuntimeFetch.PACKAGE_RUN.equals(rule);
                List<Integer> sorted = at.stream().sorted().distinct().toList();
                add(new Finding(
                        runner ? RUNTIME_DEPENDENCY : SKILL_FETCH_EXEC,
                        runner ? Severity.MEDIUM : Severity.HIGH,
                        "%s:%d".formatted(path, sorted.getFirst()),
                        "%s %s (%s)".formatted(what, messages.get(rule), lineList(sorted))));
            });
        }

        private static void collect(
                Map<String, List<Integer>> lines, Map<String, String> messages, int line, RuntimeFetch.Match match) {
            lines.computeIfAbsent(match.rule(), rule -> new ArrayList<>()).add(line);
            messages.putIfAbsent(match.rule(), match.message());
        }

        /**
         * Files of the plugin that {@code text} names, scanned and followed to {@link #MAX_DEPTH}, at
         * the severities of {@code server}, or of a hook when it is {@code null}.
         */
        private void follow(String text, String root, String location, Server server) throws IOException {
            String declaring = pathOf(location);
            String base = declaring.contains("/") ? declaring.substring(0, declaring.lastIndexOf('/')) : "";
            Set<String> visited = server == null ? scanned : serverScanned.get(server);
            Deque<Launched> queue = new ArrayDeque<>();
            for (String path : references(text, root, base)) {
                queue.add(new Launched(path, 1));
            }
            while (!queue.isEmpty()) {
                Launched next = queue.poll();
                if (!visited.add(next.path())) {
                    continue;
                }
                String content = server == null ? launchedText(next) : serverLaunchedText(next, server);
                if (content == null || next.depth() >= MAX_DEPTH) {
                    continue;
                }
                String directory = next.path().contains("/")
                        ? next.path().substring(0, next.path().lastIndexOf('/'))
                        : "";
                for (String path : references(content, root, directory)) {
                    queue.add(new Launched(path, next.depth() + 1));
                }
            }
        }

        /**
         * A file a server launches, scanned at the server's severities. A binary or oversized one is
         * not reported: a server shipped as a compiled binary is ordinary, and its bytes are pinned.
         */
        @Requirements({"GW_VETTING_0052", "GW_VETTING_0055"})
        private String serverLaunchedText(Launched launched, Server server) throws IOException {
            String text = ContentRules.text(files.read(launched.path()));
            if (text == null) {
                return null;
            }
            for (RuntimeFetch.Match match : RuntimeFetch.scan(text, true)) {
                boolean runner = RuntimeFetch.PACKAGE_RUN.equals(match.rule());
                add(new Finding(
                        runner ? server.packageRun : server.fetchExec,
                        runner ? Severity.MEDIUM : Severity.HIGH,
                        "%s:%d".formatted(launched.path(), match.line()),
                        "a script an %s launches %s".formatted(server.label, match.message())));
            }
            return text;
        }

        /** A launched file's text after scanning it, or {@code null} when it could not be read. */
        @Requirements({"GW_VETTING_0048", "GW_VETTING_0049"})
        private String launchedText(Launched launched) throws IOException {
            byte[] content = files.read(launched.path());
            String text = ContentRules.text(content);
            if (text == null) {
                // Only what a hook names itself: a data file a script mentions is not something it runs.
                if (launched.depth() == 1) {
                    add(new Finding(
                            "hook-target-unscanned",
                            Severity.MEDIUM,
                            launched.path(),
                            content == null
                                    ? "a hook runs this file, which is over the scan size limit, so no rule read it"
                                    : "a hook runs this file, which is binary, so no rule can read it"));
                }
                return null;
            }
            for (RuntimeFetch.Match match : RuntimeFetch.scan(text, true)) {
                add(new Finding(
                        match.rule(),
                        Severity.HIGH,
                        "%s:%d".formatted(launched.path(), match.line()),
                        "a script a hook launches " + match.message()));
            }
            return text;
        }

        private void add(Finding finding) {
            findings.putIfAbsent(finding.id() + "\0" + finding.location() + "\0" + finding.message(), finding);
        }

        /**
         * Files of the snapshot under {@code root} that {@code text} names: a
         * {@code ${CLAUDE_PLUGIN_ROOT}/…} reference, or a path-shaped token resolved against
         * {@code base} and the root. Only files that exist are returned, which is what keeps
         * {@code node} and {@code /usr/bin/env} from matching anything.
         */
        private List<String> references(String text, String root, String base) {
            Set<String> found = new LinkedHashSet<>();
            Matcher tokens = TOKEN.matcher(text);
            while (tokens.find()) {
                String token = tokens.group();
                List<String> bases = List.of(base, root);
                Matcher pluginRoot = PLUGIN_ROOT.matcher(token);
                if (pluginRoot.find()) {
                    token = token.substring(pluginRoot.end());
                    bases = List.of(root);
                } else {
                    Matcher variable = VARIABLE_PREFIX.matcher(token);
                    if (variable.find()) {
                        token = token.substring(variable.end());
                    }
                }
                while (token.startsWith("/")) {
                    token = token.substring(1);
                }
                if (!PATH.matcher(token).matches()
                        || (!token.contains("/") && !SCRIPT.matcher(token).matches())) {
                    continue;
                }
                for (String from : bases) {
                    String path = PluginComponents.resolve(from, token);
                    if (path != null && within(path, root) && files.paths().contains(path)) {
                        found.add(path);
                        break;
                    }
                }
            }
            return List.copyOf(found);
        }
    }

    private record Launched(String path, int depth) {}

    private static String withDefaults(String word) {
        return VARIABLE_DEFAULT.matcher(word).replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
    }

    /** {@code env} launches the program after its assignments and options; it is not the program. */
    private static void dropEnv(List<String> words) {
        while (!words.isEmpty() && "env".equals(program(words.getFirst()))) {
            words.removeFirst();
            while (!words.isEmpty()
                    && (words.getFirst().startsWith("-") || words.getFirst().matches("\\w+=.*"))) {
                String option = words.removeFirst();
                if (option.matches("-[uCS]|--unset|--chdir") && !words.isEmpty()) {
                    words.removeFirst();
                }
            }
        }
    }

    /** The script a shell wrapper is handed as an argument, or {@code null}. */
    private static String wrappedScript(List<String> words) {
        String program = program(words.getFirst());
        for (int i = 1; i < words.size() - 1; i++) {
            String flag = words.get(i).toLowerCase(Locale.ROOT);
            if (SHELLS.contains(program) && flag.matches("-[a-z]*c")) {
                return words.get(i + 1);
            }
            if ((program.equals("cmd") && (flag.equals("/c") || flag.equals("/k")))
                    || ((program.equals("powershell") || program.equals("pwsh"))
                            && (flag.equals("-c") || flag.equals("-command")))) {
                return String.join(" ", words.subList(i + 1, words.size()));
            }
        }
        return null;
    }

    /** A runner whose package is a path inside the plugin fetches nothing from a registry. */
    private static boolean localRunner(List<String> words, String root) {
        String program = program(words.getFirst());
        int operand = 1;
        if (!RUNNERS.contains(program)) {
            boolean dlx = (program.equals("pnpm") || program.equals("yarn"))
                    && words.size() > 1
                    && words.get(1).equals("dlx");
            boolean exec =
                    program.equals("npm") && words.size() > 1 && words.get(1).equals("exec");
            if (!dlx && !exec) {
                return false;
            }
            operand = 2;
        }
        while (operand < words.size() && words.get(operand).startsWith("-")) {
            operand++;
        }
        if (operand >= words.size()) {
            return false;
        }
        String target = words.get(operand);
        Matcher pluginRoot = PLUGIN_ROOT.matcher(target);
        if (pluginRoot.find()) {
            return PluginComponents.resolve(root, target.substring(pluginRoot.end())) != null;
        }
        if (!target.startsWith("./") && !target.startsWith("../")) {
            return false;
        }
        String path = PluginComponents.resolve(root, target);
        return path != null && (root.isEmpty() || path.startsWith(root + "/"));
    }

    /** A command's file name, lower-cased and without {@code .exe}: {@code /usr/bin/npx} is {@code npx}. */
    private static String program(String word) {
        String name = word.substring(word.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    /** "line 4", or "lines 2, 3, 4, 5, 6 +3 more". */
    private static String lineList(List<Integer> lines) {
        String shown = lines.stream().limit(5).map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
        String more = lines.size() > 5 ? " +%d more".formatted(lines.size() - 5) : "";
        return (lines.size() == 1 ? "line " : "lines ") + shown + more;
    }

    private static boolean vendored(String path) {
        for (String segment : path.split("/")) {
            if (VENDORED.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean underAny(String path, Set<String> directories) {
        return directories.stream().anyMatch(directory -> path.startsWith(directory + "/"));
    }

    private static boolean within(String path, String root) {
        return root.isEmpty() || path.startsWith(root + "/");
    }

    private static String pathOf(String location) {
        String path = WaiverScope.pathOf(location);
        return path == null ? "" : path;
    }

    /** The reviewer's line: when it fires, for which tools, declared by what, and what it runs. */
    private static String describe(Hook hook) {
        StringBuilder message = new StringBuilder("hook runs automatically on ").append(hook.event());
        if (hook.matcher() != null && !hook.matcher().isBlank()) {
            message.append(" for tools matching '").append(hook.matcher()).append('\'');
        }
        if (!"plugin".equals(hook.declaredBy())) {
            message.append(" while ").append(hook.declaredBy()).append(" is active");
        }
        String runs =
                hook.runs() == null ? "" : hook.runs().replaceAll("\\s+", " ").trim();
        if (runs.length() > SHOWN_COMMAND) {
            runs = runs.substring(0, SHOWN_COMMAND) + "…";
        }
        return message.append(": ")
                .append(hook.type())
                .append(' ')
                .append(runs)
                .toString()
                .trim();
    }

    /**
     * {@link PluginComponents.Files} over the vetting SPI: the tree's paths are collected with a
     * selection that opens no blob, and each read opens exactly the one it names (GW_VETTING_0030).
     */
    private record SnapshotFiles(SnapshotUnderVetting snapshot, Collection<String> paths)
            implements PluginComponents.Files {

        static SnapshotFiles of(SnapshotUnderVetting snapshot) throws IOException {
            List<String> paths = new ArrayList<>();
            snapshot.walk(
                    path -> {
                        paths.add(path);
                        return false;
                    },
                    (path, content) -> {});
            return new SnapshotFiles(snapshot, new LinkedHashSet<>(paths));
        }

        @Override
        public byte[] read(String path) throws IOException {
            byte[][] content = new byte[1][];
            snapshot.walk(path::equals, (visited, bytes) -> content[0] = bytes);
            return content[0];
        }

        /** Every file in {@code wanted}, read in one walk; a file over the size limit maps to {@code null}. */
        Map<String, byte[]> readAll(Set<String> wanted) throws IOException {
            Map<String, byte[]> contents = new HashMap<>();
            if (!wanted.isEmpty()) {
                snapshot.walk(wanted::contains, contents::put);
            }
            return contents;
        }
    }
}

package dev.skillsgateway.server.vetting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.ingestion.ManifestPolicy;
import dev.skillsgateway.server.ingestion.PluginComponents;
import dev.skillsgateway.server.policy.SkillFrontmatter;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

/**
 * Whether a snapshot's executable surface is unchanged from the served baseline
 * (GW_APPROVAL_0044.8): the one question automatic approval asks about content beyond the vetting
 * chain, because Markdown is the only change it may publish without a person.
 *
 * <p>Unchanged means three things, checked in this order:
 *
 * <ol>
 *   <li>Both trees read completely. Where {@link PluginComponents} leaves an unreadable
 *       declaration out, which is right for an inventory, this reader fails instead: oversize
 *       files, unparseable JSON or frontmatter, unscanned module files, and too many plugins.
 *   <li>Every added or modified path is inert: a regular file, not a dependency manifest, a
 *       launch target or a shebang script, with an extension on a fixed list.
 *   <li>The declarations are equal: hooks, hook modules, MCP and LSP servers, and the tools
 *       skills, commands and agents allow.
 * </ol>
 *
 * <p>The inert list is code. Widening it is a precision change, argued in a change of its own.
 */
public final class SurfaceComparison {

    /** Inert by extension, wherever they are. */
    static final Set<String> INERT = Set.of(
            "md", "markdown", "txt", "rst", "adoc", "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf", "csv", "tsv");

    /**
     * Never inert, read or not: an unchanged script can read one, and a component kind no reader
     * here knows can be declared in one. Named only so a refusal says which kind of file it was.
     */
    static final Set<String> DATA = Set.of("json", "yaml", "yml", "toml");

    private static final String MANIFEST_PATH = ".claude-plugin/marketplace.json";
    private static final String PLUGIN_JSON = ".claude-plugin/plugin.json";
    private static final long MAX_BYTES = 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param reason why the surface counts as changed, naming a path or a kind of declaration and
     *     never the content: a hook's command can carry a credential
     */
    public record Result(boolean unchanged, String reason) {

        static Result same() {
            return new Result(true, null);
        }

        static Result changed(String reason) {
            return new Result(false, reason);
        }
    }

    private SurfaceComparison() {}

    /**
     * @param baseline the served tip, or {@code null} when nothing is served: then the candidate
     *     must declare nothing and every path must be inert
     */
    @Requirements({"GW_APPROVAL_0044.8"})
    public static Result compare(Repository repo, ObjectId baseline, ObjectId candidate) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit next = walk.parseCommit(candidate);
            RevCommit served = baseline == null ? null : walk.parseCommit(baseline);
            Read after = read(repo, next);
            if (after.failure() != null) {
                return Result.changed("surface unreadable: " + after.failure());
            }
            Read before = served == null ? Read.EMPTY : read(repo, served);
            if (before.failure() != null) {
                return Result.changed("served baseline's surface unreadable: " + before.failure());
            }
            Set<String> covered = new LinkedHashSet<>(after.readPaths());
            covered.addAll(before.readPaths());
            String path = firstNonInertPath(repo, served, next, covered, after.launchTargets());
            if (path != null) {
                return Result.changed("not inert: " + path);
            }
            return declarationsDiffer(before.declarations(), after.declarations());
        }
    }

    private static Result declarationsDiffer(Map<String, String> before, Map<String, String> after) {
        for (Map.Entry<String, String> declaration : after.entrySet()) {
            if (!before.containsKey(declaration.getKey())) {
                return Result.changed("declarations changed: added or changed " + declaration.getValue());
            }
        }
        for (Map.Entry<String, String> declaration : before.entrySet()) {
            if (!after.containsKey(declaration.getKey())) {
                return Result.changed("declarations changed: removed or changed " + declaration.getValue());
            }
        }
        return Result.same();
    }

    private static String firstNonInertPath(
            Repository repo, RevCommit served, RevCommit next, Set<String> covered, Set<String> launchTargets)
            throws IOException {
        try (TreeWalk tree = new TreeWalk(repo)) {
            if (served == null) {
                tree.addTree(new EmptyTreeIterator());
            } else {
                tree.addTree(served.getTree());
            }
            tree.addTree(next.getTree());
            tree.setRecursive(true);
            tree.setFilter(TreeFilter.ANY_DIFF);
            while (tree.next()) {
                FileMode mode = tree.getFileMode(1);
                if (mode == FileMode.MISSING) {
                    continue;
                }
                String path = tree.getPathString();
                FileMode was = tree.getFileMode(0);
                if (mode != FileMode.REGULAR_FILE) {
                    return path + " (mode " + mode + ")";
                }
                if (was != FileMode.MISSING && was != mode) {
                    return path + " (mode changed from " + was + ")";
                }
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (DependencyManifests.isManifest(path) || DependencyManifests.isLockfile(name)) {
                    return path + " (dependency manifest)";
                }
                if (launchTargets.contains(path)) {
                    return path + " (launched by a declaration)";
                }
                if (startsWithShebang(repo, tree.getObjectId(1))) {
                    return path + " (shebang)";
                }
                if (path.equals(MANIFEST_PATH) || path.equals(PLUGIN_JSON) || path.endsWith("/" + PLUGIN_JSON)) {
                    if (descriptiveOnly(repo, tree.getObjectId(1), path.equals(MANIFEST_PATH))) {
                        continue;
                    }
                    return path + " (declares more than descriptive fields)";
                }
                int dot = name.lastIndexOf('.');
                String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (INERT.contains(extension)) {
                    continue;
                }
                if (DATA.contains(extension)) {
                    return path + (covered.contains(path) ? " (component file)" : " (configuration file)");
                }
                return path + " (not an inert extension)";
            }
        }
        return null;
    }

    /**
     * A manifest or {@code plugin.json} that only describes: every key, at the top level and in each
     * plugin entry, is on a fixed list. Any other key may name something that runs, including keys
     * no reader here examines, so it makes the file not inert.
     */
    private static boolean descriptiveOnly(Repository repo, ObjectId blob, boolean marketplaceManifest)
            throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(repo.open(blob).getBytes());
        } catch (IOException | RuntimeException e) {
            return false;
        }
        if (root == null || !root.isObject()) {
            return false;
        }
        if (!marketplaceManifest) {
            return keysWithin(root, PLUGIN_KEYS);
        }
        if (!keysWithin(root, MANIFEST_KEYS)) {
            return false;
        }
        for (JsonNode entry : root.path("plugins")) {
            if (!entry.isObject() || !keysWithin(entry, ENTRY_KEYS)) {
                return false;
            }
        }
        return true;
    }

    private static boolean keysWithin(JsonNode node, Set<String> allowed) {
        for (var names = node.fieldNames(); names.hasNext(); ) {
            if (!allowed.contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    private static final Set<String> MANIFEST_KEYS =
            Set.of("$schema", "name", "owner", "metadata", "description", "version", "plugins");

    private static final Set<String> ENTRY_KEYS = Set.of(
            "name",
            "source",
            "description",
            "version",
            "author",
            "homepage",
            "repository",
            "license",
            "keywords",
            "category",
            "tags");

    private static final Set<String> PLUGIN_KEYS = Set.of(
            "$schema", "name", "version", "description", "author", "homepage", "repository", "license", "keywords");

    private static boolean startsWithShebang(Repository repo, ObjectId blob) throws IOException {
        ObjectLoader loader = repo.open(blob);
        try (var stream = loader.openStream()) {
            byte[] head = stream.readNBytes(2);
            return head.length == 2 && head[0] == '#' && head[1] == '!';
        }
    }

    /** One tree's declarations, the paths the readers read, and the files declarations launch. */
    private record Read(
            Map<String, String> declarations, Set<String> readPaths, Set<String> launchTargets, String failure) {

        static final Read EMPTY = new Read(Map.of(), Set.of(), Set.of(), null);

        static Read failed(String failure) {
            return new Read(Map.of(), Set.of(), Set.of(), failure);
        }
    }

    private static Read read(Repository repo, RevCommit commit) throws IOException {
        RecordingFiles files = RecordingFiles.of(repo, commit);
        Map<String, String> roots = new LinkedHashMap<>();
        PluginComponents.Manifest manifest = null;
        if (files.paths().contains(MANIFEST_PATH)) {
            byte[] content = files.read(MANIFEST_PATH);
            if (content == null) {
                return Read.failed(MANIFEST_PATH + " is over the scan size limit");
            }
            try {
                manifest = PluginComponents.Manifest.parse(MANIFEST_PATH, content);
            } catch (IOException | RuntimeException e) {
                return Read.failed(MANIFEST_PATH + " is not valid JSON");
            }
            var entries = manifest.root().path("plugins");
            if (entries.size() > ManifestPolicy.MAX_PLUGINS) {
                return Read.failed("the manifest declares %d plugins, past the %d that are read"
                        .formatted(entries.size(), ManifestPolicy.MAX_PLUGINS));
            }
            for (int i = 0; i < entries.size(); i++) {
                var source = entries.get(i).path("source");
                String root = source.isTextual() ? PluginComponents.normalizeRoot(source.asText()) : null;
                if (root != null) {
                    roots.putIfAbsent(root, "/plugins/" + i);
                }
            }
        }
        for (String path : files.paths()) {
            if (path.equals(PLUGIN_JSON) || path.endsWith("/" + PLUGIN_JSON)) {
                roots.putIfAbsent(
                        path.substring(0, path.length() - PLUGIN_JSON.length()).replaceAll("/$", ""), null);
            }
        }
        if (roots.size() > ExecutableSurfaceVetter.MAX_PLUGIN_ROOTS) {
            return Read.failed("the snapshot declares %d plugin roots, past the %d that are read"
                    .formatted(roots.size(), ExecutableSurfaceVetter.MAX_PLUGIN_ROOTS));
        }

        Map<String, String> declarations = new LinkedHashMap<>();
        Set<String> launchTargets = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : roots.entrySet()) {
            String root = entry.getKey();
            PluginComponents.Components components =
                    PluginComponents.read(files, root, entry.getValue() == null ? null : manifest, entry.getValue());
            if (!components.hookProblems().isEmpty()) {
                PluginComponents.Problem problem = components.hookProblems().getFirst();
                return Read.failed(problem.path() + ": " + problem.message());
            }
            for (PluginComponents.Hook hook : components.hooks()) {
                declarations.put(
                        String.join(
                                "\u0000",
                                "hook",
                                hook.event(),
                                str(hook.matcher()),
                                hook.type(),
                                str(hook.runs()),
                                str(hook.declaredBy())),
                        "hook on %s, declared by %s".formatted(hook.event(), describeOwner(root, hook.declaredBy())));
                launchTargets.addAll(targets(files.paths(), root, hook.runs()));
            }
            for (PluginComponents.HookModule module : components.hookModules()) {
                if (!module.unscanned().isEmpty()) {
                    PluginComponents.Problem problem = module.unscanned().getFirst();
                    return Read.failed(problem.path() + ": " + problem.message());
                }
                declarations.put(
                        String.join("\u0000", "module", module.path(), String.valueOf(module.files())),
                        "hook module " + module.path());
                launchTargets.addAll(module.files());
            }
            server(
                    "MCP server",
                    root,
                    components.mcpServers(),
                    components.mcpCommands(),
                    declarations,
                    launchTargets,
                    files);
            server(
                    "LSP server",
                    root,
                    components.lspServers(),
                    components.lspCommands(),
                    declarations,
                    launchTargets,
                    files);
            for (String skill : components.skillFiles()) {
                String failure = tools(files, skill, "allowed-tools", "skill", declarations);
                if (failure != null) {
                    return Read.failed(failure);
                }
            }
            for (PluginComponents.Component command : components.commands()) {
                String failure = tools(files, command.path(), "allowed-tools", "command", declarations);
                if (failure != null) {
                    return Read.failed(failure);
                }
            }
            for (PluginComponents.Component agent : components.agents()) {
                String failure = tools(files, agent.path(), "tools", "agent", declarations);
                if (failure != null) {
                    return Read.failed(failure);
                }
            }
        }
        if (!files.oversize().isEmpty()) {
            return Read.failed(files.oversize().getFirst() + " is over the scan size limit");
        }
        Set<String> readPaths = new LinkedHashSet<>(files.readPaths());
        if (manifest != null) {
            readPaths.add(MANIFEST_PATH);
        }
        return new Read(Collections.unmodifiableMap(declarations), readPaths, launchTargets, null);
    }

    private static void server(
            String kind,
            String root,
            List<PluginComponents.Component> servers,
            List<PluginComponents.McpCommand> commands,
            Map<String, String> declarations,
            Set<String> launchTargets,
            RecordingFiles files) {
        for (PluginComponents.Component server : servers) {
            declarations.put(String.join("\u0000", kind, root, server.name()), "%s %s".formatted(kind, server.name()));
        }
        for (PluginComponents.McpCommand command : commands) {
            declarations.put(
                    String.join(
                            "\u0000",
                            kind + " command",
                            root,
                            command.name(),
                            str(command.command()),
                            String.valueOf(command.args())),
                    "%s %s's command".formatted(kind, command.name()));
            launchTargets.addAll(targets(files.paths(), root, command.command()));
            for (String arg : command.args()) {
                launchTargets.addAll(targets(files.paths(), root, arg));
            }
        }
    }

    /** The tools a skill, command or agent allows, from its frontmatter; a failure when that cannot be read. */
    private static String tools(
            RecordingFiles files, String path, String key, String kind, Map<String, String> declarations)
            throws IOException {
        if (!files.paths().contains(path)) {
            return null;
        }
        byte[] content = files.read(path);
        if (content == null) {
            return path + " is over the scan size limit";
        }
        SkillFrontmatter.Parsed parsed = SkillFrontmatter.parse(new String(content, StandardCharsets.UTF_8));
        if (parsed.malformed()) {
            return path + ": its frontmatter could not be read";
        }
        Object declared = parsed.absent() ? null : parsed.fields().get(key);
        if (declared != null) {
            declarations.put(
                    String.join("\u0000", kind + " tools", path, String.valueOf(declared)),
                    "%s of %s %s".formatted(key, kind, path));
        }
        return null;
    }

    /** The plugin files a command line names: what a declaration would run if the file changed. */
    private static Set<String> targets(Collection<String> paths, String root, String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return Set.of();
        }
        Set<String> found = new LinkedHashSet<>();
        for (String word : commandLine.split("[\\s\"'=;&|()<>]+")) {
            if (word.isBlank()) {
                continue;
            }
            List<String> candidates = new ArrayList<>();
            String expanded =
                    word.replace("${CLAUDE_PLUGIN_ROOT}", "/" + root).replace("$CLAUDE_PLUGIN_ROOT", "/" + root);
            candidates.add(strip(expanded));
            candidates.add(strip(PluginComponents.join(root, word)));
            for (String candidate : candidates) {
                if (paths.contains(candidate)) {
                    found.add(candidate);
                }
            }
        }
        return found;
    }

    private static String strip(String path) {
        String stripped = path;
        while (stripped.startsWith("/") || stripped.startsWith("./")) {
            stripped = stripped.startsWith("/") ? stripped.substring(1) : stripped.substring(2);
        }
        return stripped;
    }

    private static String describeOwner(String root, String declaredBy) {
        String plugin = root.isEmpty() ? "the top-level plugin" : root;
        return declaredBy == null || "plugin".equals(declaredBy) ? plugin : declaredBy + " in " + plugin;
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }

    /** One commit's tree, recording what the readers read and which reads were over the size limit. */
    private static final class RecordingFiles implements PluginComponents.Files {

        private final Repository repo;
        private final Map<String, ObjectId> blobs;
        private final Set<String> readPaths = new LinkedHashSet<>();
        private final List<String> oversize = new ArrayList<>();

        private RecordingFiles(Repository repo, Map<String, ObjectId> blobs) {
            this.repo = repo;
            this.blobs = blobs;
        }

        static RecordingFiles of(Repository repo, RevCommit commit) throws IOException {
            Map<String, ObjectId> blobs = new LinkedHashMap<>();
            try (TreeWalk tree = new TreeWalk(repo)) {
                tree.addTree(commit.getTree());
                tree.setRecursive(true);
                while (tree.next()) {
                    if (tree.getFileMode(0).getObjectType() == org.eclipse.jgit.lib.Constants.OBJ_BLOB) {
                        blobs.put(tree.getPathString(), tree.getObjectId(0).copy());
                    }
                }
            }
            return new RecordingFiles(repo, Collections.unmodifiableMap(blobs));
        }

        @Override
        public Collection<String> paths() {
            return blobs.keySet();
        }

        @Override
        public byte[] read(String path) throws IOException {
            ObjectId blob = blobs.get(path);
            if (blob == null) {
                return null;
            }
            readPaths.add(path);
            ObjectLoader loader = repo.open(blob);
            if (loader.getSize() > MAX_BYTES) {
                if (!oversize.contains(path)) {
                    oversize.add(path);
                }
                return null;
            }
            return loader.getBytes();
        }

        Set<String> readPaths() {
            return readPaths;
        }

        List<String> oversize() {
            return oversize;
        }
    }
}

package dev.skillsgateway.server.ingestion;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.policy.SkillFrontmatter;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.stream.StreamSupport;

/**
 * The one reading of the Claude Code plugin layout (GW_INGEST_0045): which commands, agents, hooks
 * and MCP servers a plugin root provides, from their default locations and from what the plugin's
 * {@code .claude-plugin/plugin.json} and its marketplace entry declare. Shared by the inventory
 * and the {@code executable-surface} vetter so the two cannot disagree about what a plugin's hooks
 * are.
 *
 * <p>Pure over a {@link Files} view, and it never throws on content: a declaration it cannot read
 * leaves that component out, and an unreadable hook declaration is returned as a
 * {@link Problem} for the vetter to report — the inventory also feeds the policy gate's facts, so
 * one malformed file must not cost the whole inventory.
 */
public final class PluginComponents {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLUGIN_JSON = ".claude-plugin/plugin.json";

    private PluginComponents() {}

    /** The files of one pinned tree: every path, and a file's bytes. */
    public interface Files {

        Collection<String> paths();

        /** The file's bytes, or {@code null} when it is over the scan size limit or absent. */
        byte[] read(String path) throws IOException;
    }

    @Schema(name = "PluginComponent", description = "A command, agent or MCP server a plugin provides")
    public record Component(
            @Schema(description = "Name: the file name without .md, the command map key, or the MCP server key")
            String name,

            @Schema(description = "Where it is defined: its file, or path:line of an inline declaration")
            String path) {}

    @Schema(
            name = "PluginHook",
            description = "A hook a plugin declares: something Claude Code runs without the user invoking it")
    public record Hook(
            @Schema(description = "The event that triggers it, e.g. SessionStart, PostToolUse, Stop")
            String event,

            @Schema(description = "The tool-name matcher that narrows the trigger, or null for every occurrence")
            String matcher,

            @Schema(
                    description = "Handler type",
                    allowableValues = {"command", "http", "mcp_tool", "prompt", "agent"})
            String type,

            @Schema(description = "What it runs: the command with its arguments, the URL, the MCP tool or the prompt")
            String runs,

            @Schema(description = "path:line of the declaration")
            String location,

            @Schema(
                    description = "plugin for a plugin hook; 'skill <name>' or 'agent <name>' for one declared"
                            + " in that skill's or agent's frontmatter, which runs while it is active")
            String declaredBy) {}

    /** A hook declaration that could not be read. */
    public record Problem(String path, String message) {}

    /**
     * A local (stdio) MCP server: what Claude Code runs to start it, located at its {@code command}
     * value. A server reached over a URL runs nothing locally and has none.
     */
    public record McpCommand(String name, String location, String command, List<String> args) {}

    /** Everything one plugin root provides beside its skills. */
    public record Components(
            List<Component> commands,
            List<Component> agents,
            List<Hook> hooks,
            List<Component> mcpServers,
            List<McpCommand> mcpCommands,
            List<Problem> hookProblems) {}

    /**
     * A parsed JSON file with the line every value starts on, keyed by JSON pointer — what locates
     * an inline declaration at {@code path:line}.
     */
    public record Manifest(String path, JsonNode root, Map<String, Integer> lines) {

        public static Manifest parse(String path, byte[] content) throws IOException {
            Map<String, Integer> lines = new HashMap<>();
            try (JsonParser parser = MAPPER.getFactory().createParser(content)) {
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.FIELD_NAME || token.isStructEnd()) {
                        continue;
                    }
                    lines.putIfAbsent(
                            parser.getParsingContext().pathAsPointer().toString(),
                            parser.currentTokenLocation().getLineNr());
                }
            }
            JsonNode root = MAPPER.readTree(content);
            if (root == null) {
                throw new IOException("empty document");
            }
            return new Manifest(path, root, Map.copyOf(lines));
        }

        /** {@code path:line} of the value at {@code pointer}, or the bare path when it has none. */
        String locate(String pointer) {
            Integer line = lines.get(pointer);
            return line == null ? path : path + ":" + line;
        }
    }

    /**
     * A manifest {@code source} as a root relative to the repository: {@code ""} for the top level,
     * {@code null} for one that is absolute or escapes the repository.
     */
    public static String normalizeRoot(String source) {
        if (source == null) {
            return null;
        }
        return resolve("", source);
    }

    /**
     * The components of the plugin at {@code root}.
     *
     * @param manifest the marketplace manifest, or {@code null} when the plugin has no entry in it
     * @param entryPointer JSON pointer of the plugin's entry in {@code manifest}, e.g. {@code /plugins/0}
     */
    @Requirements({"GW_INGEST_0045"})
    public static Components read(Files files, String root, Manifest manifest, String entryPointer) {
        Reader reader = new Reader(files, root);
        Manifest plugin = reader.json(join(root, PLUGIN_JSON), true);
        JsonNode entry = manifest == null || entryPointer == null
                ? null
                : manifest.root().at(entryPointer);
        if (entry != null && entry.isMissingNode()) {
            entry = null;
        }

        List<Component> commands = new ArrayList<>();
        List<Component> agents = new ArrayList<>();
        List<Hook> hooks = new ArrayList<>();
        List<Component> mcp = new ArrayList<>();
        List<McpCommand> mcpCommands = new ArrayList<>();

        declared(reader, plugin, entry, manifest, entryPointer, "commands", "commands", commands);
        declared(reader, plugin, entry, manifest, entryPointer, "agents", "agents", agents);

        Manifest defaultHooks = reader.json(join(root, "hooks/hooks.json"), true);
        if (defaultHooks != null) {
            reader.hooks(defaultHooks.root(), defaultHooks, "", "plugin", hooks);
        }
        if (plugin != null) {
            reader.hookDeclaration(plugin.root().path("hooks"), plugin, "/hooks", hooks);
        }
        if (entry != null) {
            reader.hookDeclaration(entry.path("hooks"), manifest, entryPointer + "/hooks", hooks);
        }

        Manifest defaultMcp = reader.json(join(root, ".mcp.json"), false);
        if (defaultMcp != null) {
            reader.mcpFile(defaultMcp, mcp, mcpCommands);
        }
        if (plugin != null) {
            reader.mcpDeclaration(plugin.root().path("mcpServers"), plugin, "/mcpServers", mcp, mcpCommands);
        }
        if (entry != null) {
            reader.mcpDeclaration(entry.path("mcpServers"), manifest, entryPointer + "/mcpServers", mcp, mcpCommands);
        }

        for (String skill : reader.skillFiles(plugin, entry)) {
            reader.frontmatterHooks(skill, "skill " + parentName(skill), hooks);
        }
        for (Component agent : agents) {
            if (!agent.path().contains(":")) {
                reader.frontmatterHooks(agent.path(), "agent " + agent.name(), hooks);
            }
        }
        return new Components(
                List.copyOf(commands),
                List.copyOf(agents),
                List.copyOf(hooks),
                List.copyOf(mcp),
                List.copyOf(mcpCommands),
                List.copyOf(reader.problems));
    }

    /**
     * Commands or agents: the manifest's paths when it declares the key — which replaces the
     * default scan — else every {@code .md} under the default directory. A marketplace entry's
     * paths are appended to a plugin.json, and stand in for one when there is none.
     */
    private static void declared(
            Reader reader,
            Manifest plugin,
            JsonNode entry,
            Manifest manifest,
            String entryPointer,
            String key,
            String defaultDirectory,
            List<Component> out) {
        boolean pluginDeclares = plugin != null && plugin.root().has(key);
        boolean entryDeclares = entry != null && entry.has(key);
        if (pluginDeclares) {
            reader.componentPaths(plugin.root().get(key), plugin, "/" + key, out);
        }
        if (entryDeclares) {
            reader.componentPaths(entry.get(key), manifest, entryPointer + "/" + key, out);
        }
        if (!pluginDeclares && !(plugin == null && entryDeclares)) {
            reader.markdownUnder(join(reader.root, defaultDirectory), out);
        }
    }

    /** Per-call state: the tree, the plugin root, and the problems found so far. */
    private static final class Reader {

        private final Files files;
        private final String root;
        private final List<Problem> problems = new ArrayList<>();

        Reader(Files files, String root) {
            this.files = files;
            this.root = root;
        }

        boolean exists(String path) {
            return path != null && files.paths().contains(path);
        }

        /**
         * A JSON file of the tree, or {@code null} when it is absent or unreadable; an unreadable
         * one is a problem only when {@code hookBearing}, since only a hook declaration is something
         * the vetter must say it could not read.
         */
        Manifest json(String path, boolean hookBearing) {
            if (!exists(path)) {
                return null;
            }
            try {
                byte[] content = files.read(path);
                if (content == null) {
                    if (hookBearing) {
                        problems.add(new Problem(path, "over the scan size limit, so its hooks could not be read"));
                    }
                    return null;
                }
                Manifest parsed = Manifest.parse(path, content);
                if (!parsed.root().isObject()) {
                    throw new IOException("not a JSON object");
                }
                return parsed;
            } catch (IOException | RuntimeException e) {
                if (hookBearing) {
                    problems.add(new Problem(path, "not valid JSON, so its hooks could not be read: " + firstLine(e)));
                }
                return null;
            }
        }

        /** A command or agent path, array of paths, or command object map. */
        void componentPaths(JsonNode value, Manifest doc, String pointer, List<Component> out) {
            if (value.isTextual()) {
                componentPath(value.asText(), out);
            } else if (value.isArray()) {
                for (JsonNode item : value) {
                    if (item.isTextual()) {
                        componentPath(item.asText(), out);
                    }
                }
            } else if (value.isObject()) {
                value.fields().forEachRemaining(field -> {
                    JsonNode source = field.getValue().path("source");
                    String path = source.isTextual() ? resolve(root, source.asText()) : null;
                    if (path != null && exists(path)) {
                        out.add(new Component(field.getKey(), path));
                    } else if (field.getValue().has("content")) {
                        out.add(new Component(field.getKey(), doc.locate(pointer + "/" + escape(field.getKey()))));
                    }
                });
            }
        }

        private void componentPath(String declared, List<Component> out) {
            String path = resolve(root, declared);
            if (path == null) {
                return;
            }
            if (exists(path)) {
                if (path.endsWith(".md")) {
                    out.add(new Component(baseName(path), path));
                }
            } else {
                markdownUnder(path, out);
            }
        }

        /** Every {@code .md} file under a directory, in tree order. */
        void markdownUnder(String directory, List<Component> out) {
            String prefix = directory.isEmpty() ? "" : directory + "/";
            for (String path : files.paths()) {
                if (path.startsWith(prefix) && path.endsWith(".md")) {
                    out.add(new Component(baseName(path), path));
                }
            }
        }

        /** A plugin.json or marketplace-entry {@code hooks} value: a path, an inline object, or an array of both. */
        void hookDeclaration(JsonNode value, Manifest doc, String pointer, List<Hook> out) {
            if (value.isTextual()) {
                Manifest file = json(resolve(root, value.asText()), true);
                if (file != null) {
                    hooks(file.root(), file, "", "plugin", out);
                }
            } else if (value.isObject()) {
                hooks(value, doc, pointer, "plugin", out);
            } else if (value.isArray()) {
                for (int i = 0; i < value.size(); i++) {
                    hookDeclaration(value.get(i), doc, pointer + "/" + i, out);
                }
            }
        }

        /** A hooks object — {@code {"hooks": {Event: [...]}}} or the bare event map. */
        void hooks(JsonNode value, Manifest doc, String pointer, String declaredBy, List<Hook> out) {
            JsonNode events = value;
            String base = pointer;
            if (value.path("hooks").isObject()) {
                events = value.get("hooks");
                base = pointer + "/hooks";
            }
            String eventsPointer = base;
            events(events, declaredBy, handlerPointer -> doc.locate(eventsPointer + handlerPointer), out);
        }

        /**
         * Event → matcher groups → handlers. {@code locate} maps a pointer relative to the event
         * map, ending at the handler's decisive field, to its {@code path:line}.
         */
        void events(
                JsonNode events,
                String declaredBy,
                java.util.function.Function<String, String> locate,
                List<Hook> out) {
            if (!events.isObject()) {
                return;
            }
            events.fields().forEachRemaining(event -> {
                JsonNode groups = event.getValue();
                for (int g = 0; g < groups.size(); g++) {
                    JsonNode group = groups.get(g);
                    String matcher = group.path("matcher").isTextual()
                            ? group.get("matcher").asText()
                            : null;
                    JsonNode handlers = group.path("hooks");
                    for (int h = 0; h < handlers.size(); h++) {
                        JsonNode handler = handlers.get(h);
                        String type = handler.path("type").asText("command");
                        String field = decisiveField(type);
                        String pointer = "/%s/%d/hooks/%d/%s".formatted(escape(event.getKey()), g, h, field);
                        out.add(new Hook(
                                event.getKey(), matcher, type, runs(type, handler), locate.apply(pointer), declaredBy));
                    }
                }
            });
        }

        /** {@code .mcp.json}: the {@code mcpServers} map, or the bare map. */
        void mcpFile(Manifest file, List<Component> out, List<McpCommand> commands) {
            JsonNode servers =
                    file.root().path("mcpServers").isObject() ? file.root().get("mcpServers") : file.root();
            mcpServers(servers, file, servers == file.root() ? "" : "/mcpServers", out, commands);
        }

        void mcpDeclaration(
                JsonNode value, Manifest doc, String pointer, List<Component> out, List<McpCommand> commands) {
            if (value.isTextual()) {
                String declared = value.asText();
                if (declared.endsWith(".json")) {
                    Manifest file = json(resolve(root, declared), false);
                    if (file != null) {
                        mcpFile(file, out, commands);
                    }
                } else {
                    // A bundle (.mcpb, .dxt) or a bundle URL: one server, named by its file.
                    out.add(new Component(baseName(declared), doc.locate(pointer)));
                }
            } else if (value.isObject()) {
                mcpServers(value, doc, pointer, out, commands);
            } else if (value.isArray()) {
                for (int i = 0; i < value.size(); i++) {
                    mcpDeclaration(value.get(i), doc, pointer + "/" + i, out, commands);
                }
            }
        }

        /** A map of servers: every one is listed; a local one also yields what it runs. */
        @Requirements({"GW_VETTING_0050"})
        private void mcpServers(
                JsonNode servers, Manifest doc, String pointer, List<Component> out, List<McpCommand> commands) {
            servers.fieldNames().forEachRemaining(name -> {
                String at = pointer + "/" + escape(name);
                out.add(new Component(name, doc.locate(at)));
                JsonNode server = servers.get(name);
                String type = server.path("type").asText("stdio");
                if ("stdio".equals(type) && server.path("command").isTextual()) {
                    List<String> args = new ArrayList<>();
                    server.path("args").forEach(arg -> args.add(arg.asText()));
                    commands.add(new McpCommand(
                            name,
                            doc.locate(at + "/command"),
                            server.get("command").asText(),
                            List.copyOf(args)));
                }
            });
        }

        /** SKILL.md files under the default {@code skills/} and under every declared skills directory. */
        List<String> skillFiles(Manifest plugin, JsonNode entry) {
            List<String> directories = new ArrayList<>();
            directories.add(join(root, "skills"));
            for (JsonNode declaring : new JsonNode[] {plugin == null ? null : plugin.root(), entry}) {
                if (declaring == null) {
                    continue;
                }
                JsonNode skills = declaring.path("skills");
                Iterator<JsonNode> items =
                        skills.isArray() ? skills.elements() : List.of(skills).iterator();
                items.forEachRemaining(item -> {
                    String directory = item.isTextual() ? resolve(root, item.asText()) : null;
                    if (directory != null && !directories.contains(directory)) {
                        directories.add(directory);
                    }
                });
            }
            List<String> skills = new ArrayList<>();
            for (String directory : directories) {
                String prefix = directory.isEmpty() ? "" : directory + "/";
                for (String path : files.paths()) {
                    if (path.startsWith(prefix) && path.endsWith("/SKILL.md") && !skills.contains(path)) {
                        skills.add(path);
                    }
                }
            }
            return skills;
        }

        /**
         * Hooks in a skill's or agent's YAML frontmatter. Located at the first line holding the
         * handler's command text, else at the {@code hooks:} key. A malformed frontmatter block is
         * {@code skill-conformance}'s to report, not a hook problem.
         */
        void frontmatterHooks(String path, String declaredBy, List<Hook> out) {
            String text;
            try {
                byte[] content = files.read(path);
                text = content == null ? null : new String(content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                text = null;
            }
            if (text == null) {
                return;
            }
            SkillFrontmatter.Parsed parsed = SkillFrontmatter.parse(text);
            if (parsed.fields() == null || !(parsed.fields().get("hooks") instanceof Map<?, ?>)) {
                return;
            }
            JsonNode events = MAPPER.valueToTree(parsed.fields().get("hooks"));
            String[] lines = text.split("\n", -1);
            int hooksKey = lineOf(lines, n -> lines[n].startsWith("hooks:") ? n : -1);
            List<Hook> found = new ArrayList<>();
            events(events, declaredBy, pointer -> path, found);
            for (Hook hook : found) {
                String needle = hook.runs();
                int line = -1;
                if (needle != null && !needle.isBlank()) {
                    String head = needle.split(" ", 2)[0];
                    line = lineOf(lines, n -> lines[n].contains(needle) ? n : -1);
                    if (line < 0) {
                        line = lineOf(lines, n -> lines[n].contains(head) ? n : -1);
                    }
                }
                int at = line > 0 ? line : hooksKey;
                out.add(new Hook(
                        hook.event(),
                        hook.matcher(),
                        hook.type(),
                        hook.runs(),
                        at > 0 ? path + ":" + at : path,
                        declaredBy));
            }
        }
    }

    /** The first 1-based line for which {@code test} answers its own index, or -1. */
    private static int lineOf(String[] lines, IntFunction<Integer> test) {
        for (int n = 0; n < lines.length; n++) {
            if (test.apply(n) >= 0) {
                return n + 1;
            }
        }
        return -1;
    }

    /** The handler field that says what it runs, which is also where it is located. */
    private static String decisiveField(String type) {
        return switch (type) {
            case "http" -> "url";
            case "mcp_tool" -> "tool";
            case "prompt", "agent" -> "prompt";
            default -> "command";
        };
    }

    /** What a handler runs, as one line a reviewer reads: exec form joins {@code command} and {@code args}. */
    private static String runs(String type, JsonNode handler) {
        return switch (type) {
            case "http" -> handler.path("url").asText(null);
            case "mcp_tool" ->
                handler.path("server").asText("") + " " + handler.path("tool").asText("");
            case "prompt", "agent" -> handler.path("prompt").asText(null);
            default -> {
                String command = handler.path("command").asText(null);
                JsonNode args = handler.path("args");
                if (command == null || !args.isArray() || args.isEmpty()) {
                    yield command;
                }
                List<String> parts = new ArrayList<>();
                parts.add(command);
                StreamSupport.stream(args.spliterator(), false)
                        .map(JsonNode::asText)
                        .forEach(parts::add);
                yield String.join(" ", parts);
            }
        };
    }

    /**
     * {@code declared} resolved against {@code root}: {@code ./} stripped, {@code .} and {@code ..}
     * segments applied, and {@code null} for an absolute path or one that escapes the root —
     * Claude Code refuses a component path outside its plugin, so the gateway never follows one.
     */
    public static String resolve(String root, String declared) {
        if (declared == null || declared.startsWith("/") || declared.contains("\\") || declared.contains("://")) {
            return null;
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : declared.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        return join(root, String.join("/", segments));
    }

    public static String join(String root, String path) {
        if (root == null || root.isEmpty()) {
            return path;
        }
        return path.isEmpty() ? root : root + "/" + path;
    }

    private static String baseName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String parentName(String path) {
        String directory = path.substring(0, path.lastIndexOf('/'));
        return directory.substring(directory.lastIndexOf('/') + 1);
    }

    /** RFC 6901 escaping of one pointer segment. */
    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static String firstLine(Exception e) {
        String message = Objects.toString(e.getMessage(), e.getClass().getSimpleName());
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}

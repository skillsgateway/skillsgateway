package dev.skillsgateway.server.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;

/** Fail-closed policy over the marketplace manifest: any parse or shape doubt is a violation. */
public final class ManifestPolicy {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MANIFEST = ".claude-plugin/marketplace.json";

    private ManifestPolicy() {}

    /** Returns a violation message, or {@code null} when the manifest is acceptable. */
    @Requirements({"GW_0003"})
    public static String validate(byte[] manifestBytes) {
        JsonNode root;
        try {
            root = MAPPER.readTree(manifestBytes);
        } catch (IOException e) {
            return MANIFEST + " is not valid JSON";
        }
        if (root == null || !root.isObject()) {
            return MANIFEST + " is not a JSON object";
        }
        JsonNode plugins = root.get("plugins");
        if (plugins == null || !plugins.isArray()) {
            return MANIFEST + " has no \"plugins\" array";
        }
        for (JsonNode plugin : plugins) {
            if (!plugin.isObject()) {
                return "plugin entry is not a JSON object";
            }
            String violation = validateSource(plugin);
            if (violation != null) {
                return violation;
            }
        }
        return null;
    }

    private static String validateSource(JsonNode plugin) {
        String name = plugin.path("name").isTextual() ? plugin.get("name").asText() : "<unnamed>";
        JsonNode source = plugin.get("source");
        if (source == null || !source.isTextual()) {
            return ("plugin '%s' has a non-local source; only relative paths inside the marketplace"
                            + " repository are allowed")
                    .formatted(name);
        }
        String path = source.asText();
        if (path.isEmpty()
                || path.contains("://")
                || path.startsWith("/")
                || path.startsWith("~")
                || path.startsWith("\\")
                || hasParentSegment(path)) {
            return ("plugin '%s' has a non-local source; only relative paths inside the marketplace"
                            + " repository are allowed")
                    .formatted(name);
        }
        return null;
    }

    private static boolean hasParentSegment(String path) {
        for (String segment : path.split("[/\\\\]")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}

package dev.skillsgateway.server.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Fail-closed policy over the marketplace manifest: any parse or shape doubt is a violation. */
@Component
public final class ManifestPolicy {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MANIFEST = ".claude-plugin/marketplace.json";

    private final ExternalSourceAdmission admission;

    /**
     * The admission is held rather than passed per call so that no caller can supply a permissive
     * one. A fail-closed gate should not have a convenient argument that opens it.
     */
    @Autowired
    public ManifestPolicy(SkillsGatewayProperties properties) {
        this(ExternalSourceAdmission.from(properties));
    }

    /**
     * The admission directly, so it can be exercised across every configuration without building a
     * whole properties record. Annotating the sibling above is what keeps Spring from having to
     * choose between them.
     */
    ManifestPolicy(ExternalSourceAdmission admission) {
        this.admission = admission;
    }

    /** Returns a violation message, or {@code null} when the manifest is acceptable. */
    @Requirements({"GW_0003", "GW_0150", "GW_0151", "GW_0152"})
    public String validate(byte[] manifestBytes) {
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
        int external = 0;
        String unresolvable = null;
        for (JsonNode plugin : plugins) {
            if (!plugin.isObject()) {
                return "plugin entry is not a JSON object";
            }
            String name = plugin.path("name").isTextual() ? plugin.get("name").asText() : "<unnamed>";
            switch (admission.decide(PluginSource.parse(plugin.get("source")), name, external)) {
                case ExternalSourceAdmission.Decision.Local ignored -> {
                    /* resolves inside the served snapshot already */
                }
                // A refusal wins over an admitted-but-unresolvable source recorded earlier: it is
                // the stronger statement, and the manifest is rejected either way.
                case ExternalSourceAdmission.Decision.Refused refused -> {
                    return refused.violation();
                }
                case ExternalSourceAdmission.Decision.Admitted admitted -> {
                    external++;
                    if (unresolvable == null) {
                        unresolvable = unresolvable(name, admitted.cloneUrl());
                    }
                }
            }
        }
        return unresolvable;
    }

    /**
     * GW_0152. Held is the state that makes a snapshot approvable and therefore publishable, so a
     * manifest still pointing a client at a URL outside the gateway must never reach it — that is
     * threat T4. Until a resolver exists, an admitted source is a rejected snapshot, worded so an
     * operator can tell "not admitted" (change the configuration) from "admitted, not resolvable"
     * (capability this gateway does not have yet). The resolver replaces this branch.
     */
    private static String unresolvable(String pluginName, String cloneUrl) {
        return ("plugin '%s' declares an external source (%s) that this gateway admits but cannot yet"
                        + " resolve into content it serves; external plugin sources are admitted"
                        + " before they can be resolved")
                .formatted(pluginName, cloneUrl);
    }
}

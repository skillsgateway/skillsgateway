package dev.skillsgateway.server.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * One external source the manifest declares and this gateway's configuration admits.
     *
     * @param index the plugin's position in the manifest's {@code plugins} array, so the rewrite is
     *     positional rather than by name — two plugins may share a name, and a rewrite that matched
     *     on one would have to guess which it meant
     */
    public record Admitted(int index, String pluginName, PluginSource source, String cloneUrl) {}

    /**
     * What the manifest is: a violation that rejects it outright, or the parsed manifest together
     * with the external sources this gateway would resolve.
     *
     * <p>Note that an admitted source is <em>not</em> a violation here. It is one to
     * {@link #validate}, which is the servability gate; this is the ingestion path's view, and
     * ingestion's next step is to resolve what it found.
     */
    public record Evaluation(String violation, ObjectNode manifest, List<Admitted> admitted) {

        public boolean rejected() {
            return violation != null;
        }
    }

    /**
     * Returns a violation message, or {@code null} when the manifest is acceptable <em>as it
     * stands</em> — which for an external source means resolved, not merely admitted.
     *
     * <p>This is the GW_0152 gate and it has exactly one meaning: {@code null} is what
     * {@code IngestionService} maps to the held state, and held is what a reviewer may approve and
     * therefore publish. A manifest still pointing a client at a URL outside the gateway must never
     * reach it — that is threat T4 — so an admitted-but-unresolved source is still a violation,
     * worded so an operator can tell "not admitted" (change the configuration) from "admitted, not
     * resolved" (the resolution failed or has not run).
     *
     * <p>It is also the post-condition {@link ManifestRewriter} runs over the manifest it produced.
     * A composite whose manifest does not pass this gate is not served, which makes the invariant
     * structural rather than a property of the rewriter being correct.
     */
    @Requirements({"GW_0003", "GW_0150", "GW_0151", "GW_0152"})
    public String validate(byte[] manifestBytes) {
        Evaluation evaluation = evaluate(manifestBytes);
        if (evaluation.rejected()) {
            return evaluation.violation();
        }
        if (evaluation.admitted().isEmpty()) {
            return null;
        }
        Admitted first = evaluation.admitted().getFirst();
        return unresolved(first.pluginName(), first.cloneUrl());
    }

    /**
     * Parses and decides the manifest, returning the external sources that were admitted rather
     * than refusing them. Refusals still short-circuit: the first refused plugin ends the walk, so
     * the default configuration produces the same violation string it always did.
     */
    @Requirements({"GW_0150", "GW_0151"})
    public Evaluation evaluate(byte[] manifestBytes) {
        JsonNode root;
        try {
            root = MAPPER.readTree(manifestBytes);
        } catch (IOException e) {
            return rejected(MANIFEST + " is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            return rejected(MANIFEST + " is not a JSON object");
        }
        JsonNode plugins = root.get("plugins");
        if (plugins == null || !plugins.isArray()) {
            return rejected(MANIFEST + " has no \"plugins\" array");
        }
        List<Admitted> admitted = new ArrayList<>();
        int index = 0;
        for (JsonNode plugin : plugins) {
            if (!plugin.isObject()) {
                return rejected("plugin entry is not a JSON object");
            }
            String name = plugin.path("name").isTextual() ? plugin.get("name").asText() : "<unnamed>";
            switch (admission.decide(PluginSource.parse(plugin.get("source")), name, admitted.size())) {
                case ExternalSourceAdmission.Decision.Local ignored -> {
                    /* resolves inside the served snapshot already */
                }
                case ExternalSourceAdmission.Decision.Refused refused -> {
                    return rejected(refused.violation());
                }
                case ExternalSourceAdmission.Decision.Admitted admittedSource ->
                    admitted.add(new Admitted(index, name, admittedSource.source(), admittedSource.cloneUrl()));
            }
            index++;
        }
        return new Evaluation(null, (ObjectNode) root, List.copyOf(admitted));
    }

    /** The violation for a source this gateway admitted and has not turned into content it serves. */
    static String unresolved(String pluginName, String cloneUrl) {
        return ("plugin '%s' declares an external source (%s) that this gateway admits but cannot yet"
                        + " resolve into content it serves")
                .formatted(pluginName, cloneUrl);
    }

    private static Evaluation rejected(String violation) {
        return new Evaluation(violation, null, List.of());
    }
}

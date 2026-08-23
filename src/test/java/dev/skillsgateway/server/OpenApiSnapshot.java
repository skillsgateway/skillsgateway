package dev.skillsgateway.server;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The published form of the OpenAPI document: object keys sorted, two-space indentation, and the
 * declared version replaced by a placeholder.
 *
 * <p>The version is normalised away because it is derived from git state and changes on every
 * commit — a snapshot carrying it would differ from the build's document on every commit, and the
 * check that they match (GW_0106) would never be green. The served document keeps the real one.
 *
 * <p>Keys are sorted because springdoc assembles the document by reflecting over the application
 * context; sorting removes the question of whether that order is stable rather than betting on it.
 * Arrays keep their order, which is part of the contract.
 */
final class OpenApiSnapshot {

    /** Stands in for the release version in the published document. Never served. */
    static final String PLACEHOLDER_VERSION = "0.0.0";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenApiSnapshot() {}

    /** Rewrites a served document into the form that is published in the repository. */
    static String publishedForm(String servedDocument) throws IOException {
        ObjectNode root = (ObjectNode) sortKeys(MAPPER.readTree(servedDocument));
        ((ObjectNode) root.required("info")).put("version", PLACEHOLDER_VERSION);

        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(
                        Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        return MAPPER.writer(printer).writeValueAsString(root) + "\n";
    }

    private static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode sorted = MAPPER.createObjectNode();
            for (String name : names) {
                sorted.set(name, sortKeys(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = MAPPER.createArrayNode();
            node.forEach(element -> sorted.add(sortKeys(element)));
            return sorted;
        }
        return node;
    }
}

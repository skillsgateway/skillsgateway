package dev.skillsgateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.JsonNode;

/**
 * The gateway's mapper writes the bytes the Jackson 2 mapper it replaced did. Stored documents,
 * served manifests and ETag-guarded objects are compared byte for byte, so a default that changed
 * them would change every one on its next write.
 *
 * <p>The expected strings were produced by Jackson 2.22 ({@code new ObjectMapper()}) from the same
 * input, before the migration.
 */
class JsonTests {

    private static final String INPUT = "{\"name\":\"m\",\"owner\":{\"n\":\"x\"},\"plugins\":[{\"name\":\"a\","
            + "\"source\":\"./p\",\"tags\":[\"x\",\"y\"],\"n\":1.50,\"e\":[],\"o\":{}}]}";

    @Test
    void pretty_printed_output_is_byte_identical_to_jackson_2() throws Exception {
        JsonNode tree = Json.MAPPER.readTree(INPUT);

        String pretty = new String(
                Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(tree), StandardCharsets.UTF_8);

        assertThat(pretty).isEqualTo("""
                        {
                          "name" : "m",
                          "owner" : {
                            "n" : "x"
                          },
                          "plugins" : [ {
                            "name" : "a",
                            "source" : "./p",
                            "tags" : [ "x", "y" ],
                            "n" : 1.5,
                            "e" : [ ],
                            "o" : { }
                          } ]
                        }""");
    }

    record Entry(String zeta, int alpha, Kind kind) {}

    enum Kind {
        FIRST;

        @com.fasterxml.jackson.annotation.JsonValue
        String wire() {
            return "first";
        }
    }

    /** Records keep declaration order (POJOs would be sorted); enums write their {@code @JsonValue}. */
    @Test
    void records_keep_declaration_order_and_enums_their_json_value() throws Exception {
        assertThat(Json.MAPPER.writeValueAsString(new Entry("z", 1, Kind.FIRST)))
                .isEqualTo("{\"zeta\":\"z\",\"alpha\":1,\"kind\":\"first\"}");
    }

    /** Content after the document is refused rather than silently dropped. */
    @Test
    void trailing_content_after_a_document_is_refused() {
        assertThatThrownBy(() -> Json.MAPPER.readTree("{\"name\":\"m\"} junk")).isInstanceOf(StreamReadException.class);
    }
}

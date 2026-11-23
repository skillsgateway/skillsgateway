package dev.skillsgateway.server.config;

import java.io.IOException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway's own JSON mapper: Jackson 3 with Jackson 3's defaults, shared by every call site.
 *
 * <p>The gateway writes JSON it later compares, hashes or serves byte for byte: rewritten marketplace
 * manifests committed into served snapshots, object-store manifests guarded by ETags, recorded
 * policy facts, the audit export, webhook payloads. For the shapes it writes (trees, records, maps,
 * timestamps already formatted as strings, enums with {@code @JsonValue}) Jackson 3's defaults
 * produce the same bytes Jackson 2 did; {@code JsonTests} holds that. On read they are lenient about
 * unknown properties and strict about trailing content after the document, which an upstream
 * manifest with junk after its closing brace now fails on.
 *
 * <p>One place, so that a later change of defaults is one decision rather than thirty. This is not
 * the mapper Spring MVC uses for the REST API; that one is Boot's.
 */
public final class Json {

    /** Shared and immutable, as every Jackson 3 mapper is. */
    public static final JsonMapper MAPPER = builder().build();

    private Json() {}

    /** A Jackson call that may fail to read or write. */
    @FunctionalInterface
    public interface Call<T> {
        T call();
    }

    /**
     * Runs {@code call}, reporting a Jackson failure as the {@link IOException} it was in Jackson 2.
     *
     * <p>Jackson 3's {@link JacksonException} is unchecked and no longer an {@code IOException}, so a
     * method that declares {@code throws IOException} around a parse would otherwise let malformed
     * input escape every caller's {@code catch (IOException e)} as a runtime exception.
     */
    public static <T> T io(Call<T> call) throws IOException {
        try {
            return call.call();
        } catch (JacksonException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** A builder starting from the same defaults, for a call site that needs one more feature. */
    public static JsonMapper.Builder builder() {
        return JsonMapper.builder();
    }
}

package dev.skillsgateway.server.approval;

import io.github.reqstool.annotations.Requirements;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The keys two plugin names collide on (GW_APPROVAL_0019.1): NFKC, invisible format characters
 * removed, the UTS #39 confusable skeleton and case folding alternated until the key stops
 * changing, then {@code -}, {@code _}, {@code .} and whitespace removed. Two names collide when they
 * share a key; there is deliberately no distance measure.
 *
 * <p>The alternation is needed because the table maps some characters to capitals ({@code 0} to
 * {@code O}) and others away from them ({@code I} to {@code l}). Pure and table-driven, so the key is
 * reproducible from the repository alone.
 */
public final class NameNormalizer {

    /** The vendored table; see the README beside it for its provenance and how to bump it. */
    static final String TABLE = "/approval/confusables-18.0.0.txt";

    /** Enough for any name the table can produce; the loop normally settles in two. */
    private static final int MAX_ROUNDS = 8;

    private static final String BYTE_ORDER_MARK = "\uFEFF";

    private static final Map<Integer, String> PROTOTYPES = load();

    private NameNormalizer() {}

    /**
     * Whether two names collide: they share a key (GW_APPROVAL_0019.1).
     */
    @Requirements({"GW_APPROVAL_0019.1"})
    public static boolean collide(String a, String b) {
        Set<String> theirs = keys(b);
        return keys(a).stream().anyMatch(theirs::contains);
    }

    /**
     * A name's keys: one with the skeleton applied before case folding, one with folding first. The
     * table reads a capital I as {@code l} and a lower-case i as itself, so which is right depends on
     * whether the capital was a letter I or a capital i — {@code cIaude} needs the first order and
     * {@code CLAUDE-SKILLS} the second. For a name with no capitals the two are the same key.
     */
    @Requirements({"GW_APPROVAL_0019.1"})
    public static Set<String> keys(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFKC)
                .codePoints()
                .filter(cp -> Character.getType(cp) != Character.FORMAT)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        Set<String> keys = new LinkedHashSet<>();
        keys.add(stripSeparators(settle(base)));
        keys.add(stripSeparators(settle(fold(base))));
        return Set.copyOf(keys);
    }

    /** Skeleton then fold, repeated until the text stops changing. */
    private static String settle(String text) {
        String key = text;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            String next = fold(skeleton(key));
            if (next.equals(key)) {
                break;
            }
            key = next;
        }
        return key;
    }

    private static String stripSeparators(String key) {
        return key.codePoints()
                .filter(cp -> cp != '-'
                        && cp != '_'
                        && cp != '.'
                        && !Character.isWhitespace(cp)
                        && !Character.isSpaceChar(cp))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /** UTS #39 skeleton: NFD, each code point replaced by its prototype, NFD again. */
    static String skeleton(String text) {
        StringBuilder mapped = new StringBuilder();
        Normalizer.normalize(text, Normalizer.Form.NFD).codePoints().forEach(cp -> {
            String prototype = PROTOTYPES.get(cp);
            if (prototype == null) {
                mapped.appendCodePoint(cp);
            } else {
                mapped.append(prototype);
            }
        });
        return Normalizer.normalize(mapped, Normalizer.Form.NFD);
    }

    /** The JDK's nearest to full case folding: upper then lower, in the root locale ({@code ß} to {@code ss}). */
    private static String fold(String text) {
        return text.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    private static Map<Integer, String> load() {
        Map<Integer, String> table = new HashMap<>();
        try (InputStream in = NameNormalizer.class.getResourceAsStream(TABLE)) {
            if (in == null) {
                throw new IllegalStateException("confusables table " + TABLE + " is missing");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                int hash = line.indexOf('#');
                String data = (hash < 0 ? line : line.substring(0, hash)).strip();
                String[] fields = data.replace(BYTE_ORDER_MARK, "").split(";");
                if (fields.length < 2) {
                    continue;
                }
                int source = Integer.parseInt(fields[0].strip(), 16);
                StringBuilder target = new StringBuilder();
                for (String cp : fields[1].strip().split("\\s+")) {
                    target.appendCodePoint(Integer.parseInt(cp, 16));
                }
                table.put(source, target.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (table.isEmpty()) {
            throw new IllegalStateException("confusables table " + TABLE + " has no mappings");
        }
        return Map.copyOf(table);
    }
}

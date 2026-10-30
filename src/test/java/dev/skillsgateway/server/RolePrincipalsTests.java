package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.roles.RolePrincipals;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The rule a grant's principal and a mapping's claim value must meet (GW_AUTH_0060, GW_AUTH_0061). */
class RolePrincipalsTests {

    /** The oracle, written as Unicode regex classes rather than the rule's code-point walk. */
    private static final Pattern FORBIDDEN_ANYWHERE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}\\p{Cs}]");

    private static final Pattern SPACE = Pattern.compile("[\\s\\p{Z}]");

    private static final String ASTRAL = "𝒜"; // U+1D49C, a letter outside the BMP

    @Test
    @SVCs({"SVC_GW_AUTH_0065"})
    void well_formed_values_pass_and_malformed_ones_name_their_fault() {
        for (String ok : List.of(
                "alice@example.com",
                "00u1a2b3c4D5e6F7g8h9",
                "Jörg Müller",
                ASTRAL + "lice",
                "a".repeat(RolePrincipals.MAX_LENGTH),
                "a".repeat(RolePrincipals.MAX_LENGTH - 1) + ASTRAL,
                "\uE000private-use")) {
            assertThat(RolePrincipals.problem(ok)).as(ok).isEmpty();
            assertThatCode(() -> RolePrincipals.requireGrantable(ok)).as(ok).doesNotThrowAnyException();
        }
        for (String bad : List.of(
                "",
                "   ",
                "  Bob ",
                "\tbob",
                "bob\u00A0",
                "\u3000bob",
                "bo\u202Eb",
                "bo\u200Bb",
                "\uFEFFbob",
                "bo\u0007b",
                "bo\u0085b",
                "bo\u2028b",
                "bo\u2029b",
                "bo\uD800b",
                "bo\uDC00b")) {
            assertThat(RolePrincipals.problem(bad)).as(bad).isPresent();
        }
        assertThat(RolePrincipals.problem(null)).isPresent();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0063", "SVC_GW_AUTH_0065"})
    void a_grant_refusal_is_422_and_never_repeats_the_value() {
        for (String bad :
                List.of("  zed-padded ", "zed\u202Ebidi", "zed\u0000nul", "z".repeat(RolePrincipals.MAX_LENGTH + 1))) {
            assertThatThrownBy(() -> RolePrincipals.requireGrantable(bad))
                    .as(bad)
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                        assertThat(e.getReason()).doesNotContain("zed").doesNotContain("zzzz");
                    });
        }
        // The bound counts code points, not UTF-16 units: 255 astral letters are 510 units.
        assertThatCode(() -> RolePrincipals.requireGrantable(ASTRAL.repeat(RolePrincipals.MAX_LENGTH)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RolePrincipals.requireGrantable(ASTRAL.repeat(RolePrincipals.MAX_LENGTH + 1)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0064", "SVC_GW_AUTH_0065"})
    void display_escapes_every_invisible_code_point_and_is_bounded() {
        assertThat(RolePrincipals.display("bo\u202Eb\u200B\u0007\u2028\uD800"))
                .isEqualTo("bo\\u202Eb\\u200B\\u0007\\u2028\\uD800");
        assertThat(RolePrincipals.display("  Bob ")).isEqualTo("  Bob ");
        assertThat(RolePrincipals.display("alice@example.com")).isEqualTo("alice@example.com");
        assertThat(RolePrincipals.display(null)).isEqualTo("null");
        String longest = RolePrincipals.display("\u202E".repeat(10_000));
        assertThat(longest.length()).isLessThanOrEqualTo(RolePrincipals.MAX_LENGTH * 6 + 1);
        assertThat(FORBIDDEN_ANYWHERE.matcher(longest).find()).isFalse();
    }

    /**
     * Seeded so a failure is reproducible from the source alone. Adjacent lone surrogates may pair
     * into one astral code point, which is exactly the case a char-wise rule would get wrong.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0065"})
    void a_randomized_sweep_agrees_with_an_independent_oracle() {
        String[] alphabet = {
            "a", "B", "é", "-", "@", " ", "\u00A0", "\u3000", "\t", "\n", "\u0007", "\u0085", "\u200B", "\u202E",
            "\uFEFF", "\u2028", "\u2029", "\uD800", "\uDC00", ASTRAL, "\uE000", "\u0378"
        };
        Random random = new Random(0x9A1_C1A1L);
        for (int i = 0; i < 20_000; i++) {
            StringBuilder value = new StringBuilder();
            int length = random.nextInt(10) == 0 ? 250 + random.nextInt(10) : random.nextInt(8);
            for (int j = 0; j < length; j++) {
                value.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String text = value.toString();
            boolean malformed = oracleMalformed(text);
            assertThat(RolePrincipals.problem(text).isPresent())
                    .as("%s", escaped(text))
                    .isEqualTo(malformed);

            boolean grantable = !malformed && text.codePointCount(0, text.length()) <= RolePrincipals.MAX_LENGTH;
            if (grantable) {
                assertThatCode(() -> RolePrincipals.requireGrantable(text)).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> RolePrincipals.requireGrantable(text))
                        .as("%s", escaped(text))
                        .isInstanceOf(ResponseStatusException.class);
            }
        }
    }

    private static boolean oracleMalformed(String text) {
        if (text.isEmpty() || FORBIDDEN_ANYWHERE.matcher(text).find()) {
            return true;
        }
        int first = text.codePointAt(0);
        int last = text.codePointBefore(text.length());
        return SPACE.matcher(Character.toString(first)).matches()
                || SPACE.matcher(Character.toString(last)).matches();
    }

    private static String escaped(String text) {
        StringBuilder out = new StringBuilder();
        text.chars().forEach(c -> out.append(c < 0x20 || c > 0x7e ? "\\u%04X".formatted(c) : String.valueOf((char) c)));
        return out.toString();
    }
}

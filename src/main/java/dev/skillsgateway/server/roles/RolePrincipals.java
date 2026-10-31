package dev.skillsgateway.server.roles;

import io.github.reqstool.annotations.Requirements;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * What a grant's principal and a mapping's claim value must look like to be matched exactly
 * (GW_AUTH_0060, GW_AUTH_0061): no surrounding whitespace and no code point that is invisible,
 * reorders text or breaks a line. Malformed values are refused, never trimmed, so what is stored is
 * what is matched.
 */
public final class RolePrincipals {

    /** OpenID Connect Core caps {@code sub} at 255 characters; an e-mail address is shorter. */
    public static final int MAX_LENGTH = 255;

    private RolePrincipals() {}

    /** Why the value cannot be matched exactly, or empty when it can. Length is not judged here. */
    @Requirements({"GW_AUTH_0060", "GW_AUTH_0061"})
    public static Optional<String> problem(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.of("must not be blank");
        }
        if (isSpace(value.codePointAt(0)) || isSpace(value.codePointBefore(value.length()))) {
            return Optional.of("must not begin or end with whitespace");
        }
        if (value.codePoints().anyMatch(RolePrincipals::isInvisible)) {
            return Optional.of("must not contain control, format, separator or unpaired surrogate characters");
        }
        return Optional.empty();
    }

    /** Why the value cannot be a grant's or a configured administrator's principal, length included. */
    @Requirements({"GW_AUTH_0060"})
    public static Optional<String> principalProblem(String principal) {
        Optional<String> problem = problem(principal);
        if (problem.isEmpty() && principal.codePointCount(0, principal.length()) > MAX_LENGTH) {
            problem = Optional.of("must be at most %d characters".formatted(MAX_LENGTH));
        }
        return problem;
    }

    /** The grants API's and the estate's check; the refusal names the rule, never the value. */
    @Requirements({"GW_AUTH_0060"})
    public static void requireGrantable(String principal) {
        Optional<String> problem = principalProblem(principal);
        if (problem.isPresent()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "the principal " + problem.get());
        }
    }

    /**
     * A malformed value made safe to log or record: invisible code points as {@code \\uXXXX},
     * cut at {@link #MAX_LENGTH} code points. A well-formed value within the bound is unchanged.
     */
    @Requirements({"GW_AUTH_0060"})
    public static String display(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder();
        int[] codePoints = value.codePoints().limit(MAX_LENGTH).toArray();
        for (int codePoint : codePoints) {
            if (isInvisible(codePoint)) {
                for (char unit : Character.toChars(codePoint)) {
                    out.append("\\u%04X".formatted((int) unit));
                }
            } else {
                out.appendCodePoint(codePoint);
            }
        }
        return codePoints.length < value.codePointCount(0, value.length())
                ? out.append('…').toString()
                : out.toString();
    }

    /** Character.isWhitespace leaves out the no-break spaces; isSpaceChar covers them. */
    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isInvisible(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL,
                    Character.FORMAT,
                    Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR,
                    Character.SURROGATE -> true;
            default -> false;
        };
    }
}

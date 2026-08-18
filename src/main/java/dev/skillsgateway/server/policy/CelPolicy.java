package dev.skillsgateway.server.policy;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.util.Map;

/**
 * The pure CEL core of policy rules (GW_0089, GW_0090): compile — parse and type-check to boolean
 * against the documented variables — at write time, evaluate bounded at decision time. CEL is
 * non-Turing-complete and terminating by design; the explicit comprehension-iteration bound closes
 * what nesting can still multiply, so a hostile expression errors out instead of hanging the gate.
 *
 * <p>No custom functions are registered: an expression can read the facts it is handed and nothing
 * else — no I/O, no state, no side effects. That property is what makes the playground (GW_0092)
 * safe to point at real snapshots.
 */
public final class CelPolicy {

    /** Aggregate comprehension budget per evaluation; beyond it, evaluation errors — and denies. */
    static final int MAX_COMPREHENSION_ITERATIONS = 100_000;

    private static final CelOptions OPTIONS = CelOptions.current()
            .comprehensionMaxIterations(MAX_COMPREHENSION_ITERATIONS)
            .build();

    /**
     * The documented variable surface (see the policy-rules guide): {@code snapshot} (id, sha,
     * marketplace, state), {@code files} ({path, size}), {@code plugins} ({name, description,
     * source}), {@code skills} ({name, path, plugin, tools}).
     */
    private static final CelCompiler COMPILER = CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(OPTIONS)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("snapshot", MapType.create(SimpleType.STRING, SimpleType.DYN))
            .addVar("files", ListType.create(MapType.create(SimpleType.STRING, SimpleType.DYN)))
            .addVar("plugins", ListType.create(MapType.create(SimpleType.STRING, SimpleType.DYN)))
            .addVar("skills", ListType.create(MapType.create(SimpleType.STRING, SimpleType.DYN)))
            .setResultType(SimpleType.BOOL)
            .build();

    private static final CelRuntime RUNTIME =
            CelRuntimeFactory.standardCelRuntimeBuilder().setOptions(OPTIONS).build();

    private CelPolicy() {}

    /** A compiled expression, ready to evaluate. */
    public record Compiled(String expression, CelAbstractSyntaxTree ast) {}

    /**
     * Parses and type-checks an expression to a boolean over the declared variables — the
     * write-time gate that keeps a non-compiling rule from ever being stored (GW_0089).
     */
    public static Compiled compile(String expression) {
        try {
            return new Compiled(expression, COMPILER.compile(expression).getAst());
        } catch (CelValidationException e) {
            throw new PolicyExpressionException(
                    "expression does not compile to a boolean over the policy variables: " + e.getMessage(), e);
        }
    }

    /**
     * Evaluates a compiled expression over the facts. Anything but a clean boolean — a runtime
     * error, an exceeded bound, a non-boolean value — raises, and the gate denies (GW_0090).
     */
    public static boolean matches(Compiled compiled, Map<String, Object> facts) {
        Object result;
        try {
            result = RUNTIME.createProgram(compiled.ast()).eval(facts);
        } catch (CelEvaluationException e) {
            throw new PolicyEvaluationException("expression failed to evaluate: " + e.getMessage(), e);
        }
        if (result instanceof Boolean matched) {
            return matched;
        }
        throw new PolicyEvaluationException(
                "expression produced %s instead of a boolean".formatted(result == null ? "null" : result.getClass()));
    }
}

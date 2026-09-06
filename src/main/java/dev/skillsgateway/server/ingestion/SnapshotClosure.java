package dev.skillsgateway.server.ingestion;

import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The closure of external plugin sources one snapshot serves (GW_0164): the upstream commit it
 * was built from, the transformation that built it, and one member per resolved external plugin.
 *
 * <p>A value, and only values. Every field is a copy of what the manifest declared or what
 * resolution produced at the moment the snapshot was made; nothing here points at a marketplace
 * row or a configuration key, because those change and the closure must not. It is the immutable
 * half of the model the security review asked to separate: the marketplace is the mutable source,
 * this is the artifact that was vetted and approved.
 *
 * <p>The transformer version is an input for the same reason it is part of the composite commit:
 * two closures built by different rewriters from the same resolved sources are different served
 * content. The admission policy is deliberately not an input — see the design of #257.
 */
public record SnapshotClosure(
        @Schema(description = "The commit ingested from upstream that the composite was built from")
        String upstreamSha,

        @Schema(description = "Identity of the rewrite implementation that produced the composite")
        String transformerVersion,

        @Schema(description = "One member per resolved external plugin, in graft-path order")
        List<Member> members) {

    public SnapshotClosure {
        Objects.requireNonNull(upstreamSha, "upstreamSha");
        Objects.requireNonNull(transformerVersion, "transformerVersion");
        members = List.copyOf(members);
    }

    /** One resolved external plugin: what was declared, what it resolved to, and where it went. */
    public record Member(
            @Schema(description = "Plugin name from the manifest")
            String pluginName,

            @Schema(description = "Source type as declared: github, git, git-subdir")
            String sourceType,

            @Schema(description = "The source exactly as the manifest declared it, e.g. an owner/repo shorthand")
            String declaredSource,

            @Schema(description = "The ref the manifest pinned, or null")
            String declaredRef,

            @Schema(description = "The commit the manifest pinned, or null")
            String declaredSha,

            @Schema(description = "The clone URL the source was resolved through")
            String cloneUrl,

            @Schema(description = "The commit of the external repository the source resolved to")
            String resolvedSha,

            @Schema(description = "The tree grafted into the composite at graftPath")
            String treeSha,

            @Schema(description = "Where the content lives inside the composite, e.g. _plugins/tools")
            String graftPath,

            @Schema(description = "Objects the grafted tree contains, measured at resolution")
            long objectCount,

            @Schema(description = "Decompressed size of the grafted tree, measured at resolution")
            long inflatedBytes) {

        /**
         * The member's fields, length-framed and in declaration order. Framing is what keeps two
         * adjacent fields from being read as one: without it {@code "toolsg" + "ithub"} and
         * {@code "tools" + "github"} would be the same bytes.
         */
        String canonical() {
            StringBuilder out = new StringBuilder();
            frame(out, pluginName);
            frame(out, sourceType);
            frame(out, declaredSource);
            frame(out, declaredRef);
            frame(out, declaredSha);
            frame(out, cloneUrl);
            frame(out, resolvedSha);
            frame(out, treeSha);
            frame(out, graftPath);
            frame(out, Long.toString(objectCount));
            frame(out, Long.toString(inflatedBytes));
            return out.toString();
        }
    }

    /** Whether there is anything to record. The empty closure is represented by no record at all. */
    public boolean isEmpty() {
        return members.isEmpty();
    }

    /**
     * SHA-256 over the canonical form: the upstream commit, the transformer version, then every
     * member's canonical line in sorted order. Sorting is what makes member order irrelevant;
     * framing is what makes every field count and nothing else.
     */
    @Requirements({"GW_0164"})
    public String digest() {
        StringBuilder canonical = new StringBuilder();
        frame(canonical, upstreamSha);
        frame(canonical, transformerVersion);
        List<String> lines = members.stream().map(Member::canonical).sorted().toList();
        frame(canonical, Integer.toString(lines.size()));
        for (String line : lines) {
            frame(canonical, line);
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    /** {@code null} and {@code ""} are different values, and the framing keeps them so. */
    private static void frame(StringBuilder out, String value) {
        if (value == null) {
            out.append("-\n");
            return;
        }
        out.append(value.length()).append(':').append(value).append('\n');
    }
}

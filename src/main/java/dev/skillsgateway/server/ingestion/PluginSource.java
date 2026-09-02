package dev.skillsgateway.server.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.reqstool.annotations.Requirements;

/**
 * A marketplace manifest's plugin {@code source}, parsed into the form it declares (GW_0150).
 *
 * <p>The manifest's source field is polymorphic — a string for a repository-relative path, an
 * object carrying a type discriminator for everything else — so a check that only asks whether the
 * value is a string refuses every external source before its type is ever read, and cannot tell an
 * operator which type they declared. Parsing first makes the type the subject of the admission
 * decision, which is what lets {@link ExternalSourceAdmission} admit one type without loosening the
 * treatment of any other.
 *
 * <p>{@link #parse} is total: every input maps to a variant, with {@link Unrecognised} carrying the
 * reason rather than an exception, so the admission switch is exhaustive at compile time and no
 * input can fall through it.
 */
public sealed interface PluginSource {

    /** The name this form goes by in configuration; {@code null} for the forms that have none. */
    String typeName();

    /** A path inside the marketplace repository — the only form that needs no resolution. */
    record Local(String path) implements PluginSource {

        @Override
        public String typeName() {
            return "local";
        }

        /** Whether the path stays inside the marketplace repository. */
        public boolean isRepositoryRelative() {
            if (path == null
                    || path.isEmpty()
                    || path.contains("://")
                    || path.startsWith("/")
                    || path.startsWith("~")
                    || path.startsWith("\\")) {
                return false;
            }
            for (String segment : path.split("[/\\\\]")) {
                if ("..".equals(segment)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** A GitHub {@code owner/repo} shorthand. */
    record GitHub(String ownerRepo) implements PluginSource {

        @Override
        public String typeName() {
            return "github";
        }

        /**
         * The URL the gateway would actually dereference. The scheme and host checks are applied to
         * this rather than to the shorthand, so a host allowlist means what an operator thinks it
         * means, and the {@code owner/repo} shape is validated here so nothing can smuggle a second
         * path segment or a host into the expansion.
         */
        public String cloneUrl() {
            if (ownerRepo == null || !ownerRepo.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) {
                return null;
            }
            return "https://github.com/" + ownerRepo;
        }
    }

    /** A git clone URL. */
    record GitUrl(String url) implements PluginSource {

        @Override
        public String typeName() {
            return "git";
        }
    }

    /** A git clone URL plus the subdirectory of it that holds the plugin. */
    record GitSubdir(String url, String path) implements PluginSource {

        @Override
        public String typeName() {
            return "git-subdir";
        }
    }

    /** An npm package. Never admissible — see {@link ExternalSourceAdmission}. */
    record Npm(String packageName) implements PluginSource {

        @Override
        public String typeName() {
            return "npm";
        }
    }

    /** A downloadable archive. Never admissible — see {@link ExternalSourceAdmission}. */
    record Archive(String url) implements PluginSource {

        @Override
        public String typeName() {
            return "archive";
        }
    }

    /**
     * Anything the parser could not place: a value that is neither a string nor a discriminated
     * object, a type the gateway does not implement, or a known type missing the field it needs.
     * {@code detail} is what the violation message tells the operator.
     */
    record Unrecognised(String detail) implements PluginSource {

        @Override
        public String typeName() {
            return null;
        }
    }

    /**
     * Places a manifest's {@code source} value into one of the forms above. Never throws and never
     * returns {@code null}.
     */
    @Requirements({"GW_0150"})
    static PluginSource parse(JsonNode source) {
        if (source == null || source.isNull()) {
            return new Unrecognised("no source");
        }
        if (source.isTextual()) {
            return new Local(source.asText());
        }
        if (!source.isObject()) {
            return new Unrecognised("a source that is neither a path nor an object");
        }
        JsonNode discriminator = source.get("source");
        if (discriminator == null || !discriminator.isTextual()) {
            return new Unrecognised("an object source with no \"source\" type");
        }
        String type = discriminator.asText();
        return switch (type) {
            case "github" ->
                field(source, "repo")
                        .<PluginSource>map(GitHub::new)
                        .orElseGet(() -> new Unrecognised("a github source with no \"repo\""));
            case "git", "url" ->
                field(source, "url")
                        .<PluginSource>map(GitUrl::new)
                        .orElseGet(() -> new Unrecognised("a %s source with no \"url\"".formatted(type)));
            case "git-subdir" ->
                field(source, "url")
                        .flatMap(url -> field(source, "path").<PluginSource>map(path -> new GitSubdir(url, path)))
                        .orElseGet(() -> new Unrecognised("a git-subdir source with no \"url\" and \"path\""));
            case "npm" ->
                field(source, "package")
                        .<PluginSource>map(Npm::new)
                        .orElseGet(() -> new Unrecognised("an npm source with no \"package\""));
            case "archive" ->
                field(source, "url")
                        .<PluginSource>map(Archive::new)
                        .orElseGet(() -> new Unrecognised("an archive source with no \"url\""));
            default -> new Unrecognised("source type '%s'".formatted(type));
        };
    }

    private static java.util.Optional<String> field(JsonNode object, String name) {
        JsonNode value = object.get(name);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? java.util.Optional.of(value.asText())
                : java.util.Optional.empty();
    }
}

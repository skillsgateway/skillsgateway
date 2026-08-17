package dev.skillsgateway.server.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.persistence.MarketplaceRepository.ForgeMetadata;
import io.github.reqstool.annotations.Requirements;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

/**
 * Best-effort forge metadata at registration (GW_0021): project name, description, and last
 * upstream update from the forge's REST API. Supported forges: GitHub, GitLab, Bitbucket Cloud,
 * Bitbucket Server/Data Center, Azure DevOps, and the Gitea/Forgejo API shape as the fallback
 * (covers Codeberg and self-hosted instances). Any failure (unknown forge, private repo, network,
 * timeout) resolves to empty — registration never depends on it.
 */
@Service
public class ForgeMetadataService {

    private static final Logger log = LoggerFactory.getLogger(ForgeMetadataService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;

    public ForgeMetadataService() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** Which API to call and where the fields live in its response (JSON pointers). */
    record ForgeTarget(
            String forge, String apiUrl, String namePointer, String descriptionPointer, String updatedPointer) {}

    @Requirements({"GW_0021"})
    public Optional<ForgeMetadata> resolve(String cloneUrl) {
        try {
            Optional<ForgeTarget> target = target(cloneUrl);
            if (target.isEmpty()) {
                return Optional.empty();
            }
            return fetch(target.get());
        } catch (Exception e) {
            log.debug("forge metadata unavailable for {}: {}", cloneUrl, e.toString());
            return Optional.empty();
        }
    }

    /** Pure URL → forge API mapping; package-private for unit testing. */
    static Optional<ForgeTarget> target(String cloneUrl) throws Exception {
        URI uri = new URI(cloneUrl);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            return Optional.empty();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String base = scheme + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String path = trimmedPath(uri.getPath());

        if (host.equals("github.com")) {
            String project = ownerRepo(path);
            if (project == null) {
                return Optional.empty();
            }
            return Optional.of(new ForgeTarget(
                    "github", "https://api.github.com/repos/" + project, "/full_name", "/description", "/pushed_at"));
        }
        if (host.equals("bitbucket.org")) {
            String project = ownerRepo(path);
            if (project == null) {
                return Optional.empty();
            }
            return Optional.of(new ForgeTarget(
                    "bitbucket",
                    "https://api.bitbucket.org/2.0/repositories/" + project,
                    "/full_name",
                    "/description",
                    "/updated_on"));
        }
        if (host.equals("dev.azure.com") || host.endsWith(".visualstudio.com")) {
            // https://dev.azure.com/{org}/{project}/_git/{repo}
            String[] segments = path.split("/");
            int gitIdx = indexOf(segments, "_git");
            if (gitIdx < 2 || gitIdx + 1 >= segments.length) {
                return Optional.empty();
            }
            String org = host.endsWith(".visualstudio.com") ? host.substring(0, host.indexOf('.')) : segments[0];
            String project = segments[gitIdx - 1];
            String repo = segments[gitIdx + 1];
            return Optional.of(new ForgeTarget(
                    "azure-devops",
                    "https://dev.azure.com/%s/%s/_apis/git/repositories/%s?api-version=7.1"
                            .formatted(org, project, repo),
                    "/name",
                    "/project/description",
                    "/project/lastUpdateTime"));
        }
        if (host.contains("bitbucket")) {
            // Bitbucket Server/DC clone URL: https://host/scm/{projectKey}/{slug}.git
            String[] segments = path.split("/");
            if (segments.length >= 3 && segments[0].equals("scm")) {
                return Optional.of(new ForgeTarget(
                        "bitbucket-server",
                        base + "/rest/api/1.0/projects/%s/repos/%s".formatted(segments[1], segments[2]),
                        "/name",
                        "/description",
                        null));
            }
            return Optional.empty();
        }
        if (host.contains("gitlab")) {
            String project = ownerRepo(path);
            if (project == null) {
                return Optional.empty();
            }
            String encoded = UriUtils.encode(path, "UTF-8");
            return Optional.of(new ForgeTarget(
                    "gitlab",
                    base + "/api/v4/projects/" + encoded,
                    "/path_with_namespace",
                    "/description",
                    "/last_activity_at"));
        }
        // Anything else: try the Gitea/Forgejo API shape (also Codeberg).
        String project = ownerRepo(path);
        if (project == null) {
            return Optional.empty();
        }
        return Optional.of(new ForgeTarget(
                "gitea", base + "/api/v1/repos/" + project, "/full_name", "/description", "/updated_at"));
    }

    private Optional<ForgeMetadata> fetch(ForgeTarget target) {
        try {
            String raw = restClient.get().uri(target.apiUrl()).retrieve().body(String.class);
            JsonNode body = raw == null ? null : MAPPER.readTree(raw);
            if (body == null || !body.isObject()) {
                return Optional.empty();
            }
            String project = text(body, target.namePointer());
            String description = text(body, target.descriptionPointer());
            Instant updatedAt = parseInstant(text(body, target.updatedPointer()));
            if (project == null && description == null && updatedAt == null) {
                return Optional.empty();
            }
            return Optional.of(new ForgeMetadata(target.forge(), project, description, updatedAt));
        } catch (Exception e) {
            log.debug("forge metadata lookup failed at {}: {}", target.apiUrl(), e.toString());
            return Optional.empty();
        }
    }

    private static String text(JsonNode body, String pointer) {
        if (pointer == null) {
            return null;
        }
        JsonNode node = body.at(pointer);
        return node.isTextual() ? node.asText() : null;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String trimmedPath(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.replaceAll("^/+", "").replaceAll("/+$", "");
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4);
        }
        return trimmed;
    }

    /** Requires at least owner/repo; keeps the full path (GitLab nested groups). */
    private static String ownerRepo(String trimmedPath) {
        String[] segments = trimmedPath.split("/");
        if (segments.length < 2 || segments[0].isBlank() || segments[1].isBlank()) {
            return null;
        }
        return trimmedPath;
    }

    private static int indexOf(String[] segments, String value) {
        for (int i = 0; i < segments.length; i++) {
            if (value.equals(segments[i])) {
                return i;
            }
        }
        return -1;
    }
}

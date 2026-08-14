package io.github.jimisola.skillsgateway.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jimisola.skillsgateway.persistence.MarketplaceRepository.ForgeMetadata;
import io.github.reqstool.annotations.Requirements;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

/**
 * Best-effort forge metadata at registration (GW_0021): project name, description, and last
 * upstream update from the forge's REST API. Any failure (unknown forge, private repo, network,
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

    @Requirements({"GW_0021"})
    public Optional<ForgeMetadata> resolve(String cloneUrl) {
        try {
            URI uri = new URI(cloneUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                return Optional.empty();
            }
            String project = projectPath(uri.getPath());
            if (project == null) {
                return Optional.empty();
            }
            String host = uri.getHost();
            String base = scheme + "://" + host + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            if (host.equalsIgnoreCase("github.com")) {
                return fetch("github", "https://api.github.com/repos/" + project, "full_name", "pushed_at");
            }
            if (host.toLowerCase().contains("gitlab")) {
                String encoded = UriUtils.encode(project, "UTF-8");
                return fetch("gitlab", base + "/api/v4/projects/" + encoded, "path_with_namespace", "last_activity_at");
            }
            // Anything else: try the Gitea/Forgejo API shape.
            return fetch("gitea", base + "/api/v1/repos/" + project, "full_name", "updated_at");
        } catch (Exception e) {
            log.debug("forge metadata unavailable for {}: {}", cloneUrl, e.toString());
            return Optional.empty();
        }
    }

    private Optional<ForgeMetadata> fetch(String forge, String apiUrl, String nameField, String updatedField) {
        try {
            String raw = restClient.get().uri(apiUrl).retrieve().body(String.class);
            JsonNode body = raw == null ? null : MAPPER.readTree(raw);
            if (body == null || !body.isObject()) {
                return Optional.empty();
            }
            String project =
                    body.path(nameField).isTextual() ? body.get(nameField).asText() : null;
            String description = body.path("description").isTextual()
                    ? body.get("description").asText()
                    : null;
            Instant updatedAt = parseInstant(body.path(updatedField).asText(null));
            if (project == null && description == null && updatedAt == null) {
                return Optional.empty();
            }
            return Optional.of(new ForgeMetadata(forge, project, description, updatedAt));
        } catch (Exception e) {
            log.debug("forge metadata lookup failed at {}: {}", apiUrl, e.toString());
            return Optional.empty();
        }
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

    /** "/owner/repo.git" → "owner/repo"; anything not owner/repo-shaped → null. */
    private static String projectPath(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.replaceAll("^/+", "").replaceAll("/+$", "");
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4);
        }
        String[] segments = trimmed.split("/");
        if (segments.length < 2 || segments[0].isBlank() || segments[1].isBlank()) {
            return null;
        }
        // GitLab supports nested groups; keep the full path. GitHub/Gitea use the first two.
        return trimmed;
    }
}

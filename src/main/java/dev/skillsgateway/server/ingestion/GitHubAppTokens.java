package dev.skillsgateway.server.ingestion;

import dev.skillsgateway.server.config.SkillsGatewayProperties.GitHubApp;
import java.net.URI;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** STUB — the RED run's placeholder; replaced by the implementation. */
final class GitHubAppTokens {

    static final String DEFAULT_API = "https://api.github.com";

    enum Phase {
        LOOKUP,
        TOKEN
    }

    record Repo(String owner, String name) {}

    record Minted(String token, boolean cached) {}

    private GitHubAppTokens() {}

    static GitHubAppTokens load(String name, GitHubApp config, Clock clock) {
        return new GitHubAppTokens();
    }

    static Optional<Repo> repository(String url) {
        return Optional.empty();
    }

    static RSAPrivateKey readKey(String pem) {
        throw new IllegalArgumentException("stub");
    }

    static UpstreamFailure classify(Phase phase, int status, String message, String date, Instant now, String where) {
        return new UpstreamFailure(UpstreamFailure.OTHER, "stub", "stub");
    }

    URI apiBase() {
        return URI.create(DEFAULT_API);
    }

    String assertion() {
        return "";
    }

    Minted token(String url, boolean renew) {
        throw new UnsupportedOperationException("stub");
    }

    void forget(String url) {}

    List<String> secrets() {
        return List.of();
    }
}

package dev.skillsgateway.server.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.skillsgateway.server.config.SkillsGatewayProperties.GitHubApp;
import io.github.reqstool.annotations.Requirements;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The installation tokens one GitHub App credential entry reads its upstreams with (GW_INGEST_0056):
 * an assertion signed with the App's key, exchanged at the configured API for a token scoped to the
 * one repository being read, and cached until shortly before it expires. The key, the assertion
 * and the tokens are never logged or quoted (GW_AUTH_0049).
 */
final class GitHubAppTokens {

    private static final Logger log = LoggerFactory.getLogger(GitHubAppTokens.class);

    static final String DEFAULT_API = "https://api.github.com";

    /** A token is reused while its stated expiry is further away than this (GW_INGEST_0058). */
    static final Duration RENEW_MARGIN = Duration.ofMinutes(5);

    /** GitHub's documented allowance for drift: the assertion is dated this far in the past. */
    static final Duration BACKDATE = Duration.ofSeconds(60);

    /** GitHub refuses an assertion that expires more than ten minutes after it was issued. */
    static final Duration ASSERTION_LIFETIME = Duration.ofMinutes(9);

    /** A server {@code Date} further off than this, on a 401, is reported as clock skew. */
    static final Duration SKEW_TOLERANCE = Duration.ofSeconds(30);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MESSAGE_LIMIT = 200;

    /** What a GitHub owner or repository name can be made of; nothing that can move an API path. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern PEM =
            Pattern.compile("-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/=]+");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Never follows a redirect: the assertion goes to the configured API and nowhere else
     * (GW_AUTH_0051). The JVM's proxy selector, as JGit's transport uses.
     */
    private static final HttpClient HTTP = client();

    private static HttpClient client() {
        HttpClient.Builder builder =
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER);
        ProxySelector proxies = ProxySelector.getDefault();
        if (proxies != null) {
            builder.proxy(proxies);
        }
        return builder.build();
    }

    enum Phase {
        LOOKUP,
        TOKEN
    }

    /** The owner and repository a marketplace URL names. */
    record Repo(String owner, String name) {

        String key() {
            return (owner + "/" + name).toLowerCase(Locale.ROOT);
        }
    }

    /** An installation token, and whether it came from the cache (and so may be renewed once). */
    record Minted(String token, boolean cached) {

        @Override
        public String toString() {
            return "Minted[cached=" + cached + "]";
        }
    }

    private record Cached(String token, Instant expiresAt) {}

    private record Answer(int status, String body, String date) {}

    private final String appId;
    private final String pem;
    private final RSAPrivateKey key;
    private final Long installationId;
    private final URI apiBase;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private GitHubAppTokens(
            String appId, String pem, RSAPrivateKey key, Long installationId, URI apiBase, Clock clock) {
        this.appId = appId;
        this.pem = pem;
        this.key = key;
        this.installationId = installationId;
        this.apiBase = apiBase;
        this.clock = clock;
    }

    /**
     * Validates one entry's {@code github-app} block; anything that cannot work as written stops
     * startup, naming the entry and never the key (GW_INGEST_0060).
     */
    @Requirements({"GW_INGEST_0060", "GW_AUTH_0049"})
    static GitHubAppTokens load(String name, GitHubApp config, Clock clock) {
        String where = name + ": github-app.";
        UpstreamCredentials.requireUsable(name, "github-app.app-id", config.appId());
        if (!ID.matcher(config.appId().trim()).matches()) {
            throw new IllegalStateException(where + "app-id must be the App's numeric id");
        }
        Long installation = null;
        String installationText = config.installationId();
        if (installationText != null && !installationText.isBlank()) {
            UpstreamCredentials.requireUsable(name, "github-app.installation-id", installationText);
            if (!ID.matcher(installationText.trim()).matches()) {
                throw new IllegalStateException(where + "installation-id must be a numeric installation id");
            }
            installation = Long.valueOf(installationText.trim());
        }
        UpstreamCredentials.requireUsable(name, "github-app.private-key", config.privateKey());
        RSAPrivateKey key;
        try {
            key = readKey(config.privateKey());
        } catch (IllegalArgumentException e) {
            // The cause is dropped on purpose: a parser's message may quote the input.
            throw new IllegalStateException(where + "private-key is not a readable RSA private key in PEM form"
                    + " (PKCS#1 \"BEGIN RSA PRIVATE KEY\" or PKCS#8 \"BEGIN PRIVATE KEY\", unencrypted): "
                    + e.getMessage());
        }
        return new GitHubAppTokens(
                config.appId().trim(), config.privateKey(), key, installation, apiBase(name, config.apiUrl()), clock);
    }

    /** The API base: configuration only (GW_INGEST_0061); https, or http to loopback only. */
    private static URI apiBase(String name, String configured) {
        String text = configured == null || configured.isBlank() ? DEFAULT_API : configured.trim();
        UpstreamCredentials.requireUsable(name, "github-app.api-url", text);
        String refusal = name + ": github-app.api-url must be an absolute https URL with no userinfo, query or"
                + " fragment; http is accepted only to a loopback host";
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(refusal);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean allowed = "https".equals(scheme)
                || ("http".equals(scheme) && uri.getHost() != null && UpstreamCredentials.isLoopback(uri.getHost()));
        if (!allowed
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalStateException(refusal);
        }
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return URI.create(text);
    }

    /**
     * An RSA private key from PEM. PKCS#1 ({@code BEGIN RSA PRIVATE KEY}, what GitHub issues) is
     * wrapped in a PKCS#8 envelope, which the platform reads without BouncyCastle. A {@code \n}
     * written as two characters, as some secret stores hold a multi-line value, is a line break.
     * Messages never quote the input.
     */
    static RSAPrivateKey readKey(String pem) {
        String text = pem == null ? "" : pem.replace("\\n", "\n");
        Matcher block = PEM.matcher(text);
        if (!block.find()) {
            throw new IllegalArgumentException("no PEM private key block");
        }
        String label = block.group(1);
        String body = block.group(2).replaceAll("\\s", "");
        if (!label.equals("RSA PRIVATE KEY") && !label.equals("PRIVATE KEY")) {
            throw new IllegalArgumentException("a PEM block labelled '" + label + "' is not a private key");
        }
        if (!BASE64.matcher(body).matches()) {
            throw new IllegalArgumentException("the key is not valid base64");
        }
        try {
            byte[] der = Base64.getDecoder().decode(body);
            byte[] info = label.equals("RSA PRIVATE KEY") ? pkcs8(der) : der;
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(info));
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
            throw new IllegalArgumentException("the key could not be read as an RSA private key");
        }
    }

    /** PrivateKeyInfo { version 0, rsaEncryption with NULL parameters, the PKCS#1 key as an OCTET STRING }. */
    private static byte[] pkcs8(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] algorithm = {
            0x30,
            0x0d,
            0x06,
            0x09,
            0x2a,
            (byte) 0x86,
            0x48,
            (byte) 0x86,
            (byte) 0xf7,
            0x0d,
            0x01,
            0x01,
            0x01,
            0x05,
            0x00
        };
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.writeBytes(version);
        inner.writeBytes(algorithm);
        inner.writeBytes(der(0x04, pkcs1));
        return der(0x30, inner.toByteArray());
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = (32 - Integer.numberOfLeadingZeros(length) + 7) / 8;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write(length >>> (8 * i));
            }
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    /**
     * The owner and repository a clone URL names: exactly two plain path segments, the second
     * without {@code .git} (GW_INGEST_0061). Nothing else reaches an API path.
     */
    @Requirements({"GW_INGEST_0061"})
    static Optional<Repo> repository(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException | NullPointerException e) {
            return Optional.empty();
        }
        if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return Optional.empty();
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String[] segments = path.split("/", -1);
        if (segments.length != 2) {
            return Optional.empty();
        }
        String owner = segments[0];
        String name = segments[1].endsWith(".git") ? segments[1].substring(0, segments[1].length() - 4) : segments[1];
        if (!plain(owner) || !plain(name)) {
            return Optional.empty();
        }
        return Optional.of(new Repo(owner, name));
    }

    private static boolean plain(String name) {
        return NAME.matcher(name).matches() && !name.equals(".") && !name.equals("..");
    }

    URI apiBase() {
        return apiBase;
    }

    /**
     * A new assertion for the App: RS256, issued {@link #BACKDATE} ago, expiring within ten
     * minutes, never kept (GW_AUTH_0050).
     */
    @Requirements({"GW_AUTH_0050"})
    String assertion() {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(appId)
                .issueTime(Date.from(now.minus(BACKDATE)))
                .expirationTime(Date.from(now.plus(ASSERTION_LIFETIME)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("the GitHub App assertion could not be signed", e);
        }
        return jwt.serialize();
    }

    /**
     * An installation token for the repository {@code url} names: the cached one while it has more
     * than {@link #RENEW_MARGIN} left and {@code renew} is false, a new one otherwise
     * (GW_INGEST_0056, GW_INGEST_0058). {@code renew} drops the cached token first.
     */
    @Requirements({"GW_INGEST_0056", "GW_INGEST_0058"})
    Minted token(String url, boolean renew) {
        Repo repo = repository(url)
                .orElseThrow(() -> new UpstreamException(
                        new UpstreamFailure(
                                UpstreamFailure.NOT_A_GITHUB_REPOSITORY,
                                "A GitHub App credential reads https://<host>/<owner>/<repository>[.git]; register"
                                        + " the repository's own clone URL.",
                                "the URL's path is not a plain owner and repository name"),
                        null));
        Cached cached = renew ? cache.remove(repo.key()) : cache.get(repo.key());
        if (!renew && usable(cached)) {
            return new Minted(cached.token(), true);
        }
        Cached fresh = mint(repo, cached == null ? null : cached.token());
        if (usable(fresh)) {
            cache.put(repo.key(), fresh);
        }
        log.debug(
                "minted a GitHub App installation token for {}/{}, expiring at {}",
                repo.owner(),
                repo.name(),
                fresh.expiresAt());
        return new Minted(fresh.token(), false);
    }

    private boolean usable(Cached cached) {
        return cached != null && cached.expiresAt().isAfter(clock.instant().plus(RENEW_MARGIN));
    }

    /** Drops the cached token for the repository {@code url} names (GW_INGEST_0058). */
    void forget(String url) {
        repository(url).ifPresent(repo -> cache.remove(repo.key()));
    }

    /** The key and every cached token, for scrubbing (GW_AUTH_0049). */
    List<String> secrets() {
        List<String> secrets = new ArrayList<>();
        secrets.add(pem);
        cache.values().forEach(cached -> secrets.add(cached.token()));
        return secrets;
    }

    /**
     * The exchange: the installation (configured, or looked up for the repository), then a token for
     * that one repository with contents read only (GW_INGEST_0057).
     */
    @Requirements({"GW_INGEST_0057", "GW_AUTH_0051"})
    private Cached mint(Repo repo, String replaced) {
        String jwt = assertion();
        List<String> scrub = new ArrayList<>(List.of(jwt, pem));
        if (replaced != null) {
            scrub.add(replaced);
        }
        long installation;
        if (installationId != null) {
            installation = installationId;
        } else {
            String path = "/repos/" + repo.owner() + "/" + repo.name() + "/installation";
            Answer answer = call("GET", path, jwt, null, scrub);
            if (answer.status() != 200) {
                throw refusal(Phase.LOOKUP, answer, "GET " + path, scrub);
            }
            JsonNode id = json(answer.body()).path("id");
            if (!id.canConvertToLong()) {
                throw refusal(Phase.LOOKUP, answer, "GET " + path, scrub);
            }
            installation = id.asLong();
        }
        String path = "/app/installations/" + installation + "/access_tokens";
        String body = "{\"repositories\":[\"" + repo.name() + "\"],\"permissions\":{\"contents\":\"read\"}}";
        Answer answer = call("POST", path, jwt, body, scrub);
        JsonNode issued = answer.status() == 201 ? json(answer.body()) : MAPPER.missingNode();
        String token = issued.path("token").asText("");
        if (token.isEmpty()) {
            throw refusal(Phase.TOKEN, answer, "POST " + path, scrub);
        }
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(issued.path("expires_at").asText(""));
        } catch (DateTimeParseException e) {
            // No stated expiry: use it for this read and never again.
            expiresAt = clock.instant();
        }
        return new Cached(token, expiresAt);
    }

    private Answer call(String method, String path, String jwt, String body, List<String> scrub) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + jwt);
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(
                    response.statusCode(),
                    response.body(),
                    response.headers().firstValue("Date").orElse(null));
        } catch (IOException e) {
            throw unreachable(method + " " + path, e, scrub);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable(method + " " + path, e, scrub);
        }
    }

    private UpstreamException unreachable(String where, Exception e, List<String> scrub) {
        String detail = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + e.getMessage();
        UpstreamFailure failure = new UpstreamFailure(
                        UpstreamFailure.APP_API_UNREACHABLE,
                        "Check the credential's github-app.api-url, and that the gateway's network (proxy,"
                                + " firewall, egress) lets it reach the GitHub API.",
                        apiBase + " (" + where + "): " + detail)
                .scrub(scrub);
        // The cause is not attached: its message is already in the root cause, scrubbed.
        return new UpstreamException(failure, null);
    }

    private UpstreamException refusal(Phase phase, Answer answer, String where, List<String> scrub) {
        String message = message(answer.body());
        UpstreamFailure failure = classify(
                        phase, answer.status(), message, answer.date(), clock.instant(), where + " at " + apiBase)
                .scrub(scrub);
        return new UpstreamException(failure, null);
    }

    /**
     * Why the API did not issue a token, from its status, its message and its clock (GW_INGEST_0059).
     * A pure function of its arguments, so each answer's reading is tested on its own.
     */
    @Requirements({"GW_INGEST_0059"})
    static UpstreamFailure classify(Phase phase, int status, String message, String date, Instant now, String where) {
        String text = message == null ? "" : message;
        String rootCause = "HTTP " + status + " from " + where + (text.isEmpty() ? "" : ": " + text);
        if (status == 404 && phase == Phase.LOOKUP || status == 422 && phase == Phase.TOKEN) {
            return new UpstreamFailure(
                    UpstreamFailure.APP_NOT_INSTALLED,
                    "Install the GitHub App on the repository's owner and, if the installation lists"
                            + " selected repositories, add this one.",
                    rootCause);
        }
        if (status == 404 && phase == Phase.TOKEN) {
            return new UpstreamFailure(
                    UpstreamFailure.APP_INSTALLATION_NOT_FOUND,
                    "Check the credential's github-app.installation-id, or reinstall the App and use the new"
                            + " installation's id (or omit it to have the gateway look it up).",
                    rootCause);
        }
        if (status == 403 && text.toLowerCase(Locale.ROOT).contains("suspend")) {
            return new UpstreamFailure(
                    UpstreamFailure.APP_SUSPENDED,
                    "Unsuspend the App's installation in the owner's GitHub settings.",
                    rootCause);
        }
        if (status == 401) {
            if (text.contains("'iat'") || text.contains("'exp'") || skewed(date, now)) {
                return new UpstreamFailure(
                        UpstreamFailure.APP_CLOCK_SKEW,
                        "Synchronise the gateway host's clock (NTP); GitHub refuses an App assertion dated in"
                                + " its future or expiring more than ten minutes ahead.",
                        rootCause);
            }
            return new UpstreamFailure(
                    UpstreamFailure.APP_KEY_REFUSED,
                    "Check that github-app.app-id is the App the private key belongs to, and that the key has"
                            + " not been deleted from the App's settings.",
                    rootCause);
        }
        return new UpstreamFailure(
                UpstreamFailure.APP_NO_TOKEN,
                "Check the root cause; if the GitHub API is rate-limiting or failing, try again later.",
                rootCause);
    }

    private static boolean skewed(String date, Instant now) {
        if (date == null) {
            return false;
        }
        try {
            Instant server = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
            return Duration.between(server, now).abs().compareTo(SKEW_TOLERANCE) > 0;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** GitHub's own {@code message}, or the start of the body; never more than a line's worth. */
    private static String message(String body) {
        String text = json(body).path("message").asText("");
        if (text.isEmpty() && body != null) {
            text = body.strip();
        }
        text = text.replaceAll("\\s+", " ");
        return text.length() > MESSAGE_LIMIT ? text.substring(0, MESSAGE_LIMIT) + "…" : text;
    }

    private static JsonNode json(String body) {
        try {
            return body == null || body.isBlank() ? MAPPER.missingNode() : MAPPER.readTree(body);
        } catch (IOException e) {
            return MAPPER.missingNode();
        }
    }
}

package dev.skillsgateway.server.ingestion;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.config.SkillsGatewayProperties.UpstreamCredential;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The credentials private marketplace upstreams are read with (GW_INGEST_0050), and the one place
 * that decides whether a request carries one (GW_INGEST_0051).
 *
 * <p>JGit is never handed a {@code CredentialsProvider}: it would keep an established credential
 * across a redirect to another port, scheme or path of the same host name. Instead the credential
 * rides on a per-fetch connection factory that decides again for every request, including each
 * redirect hop.
 */
@Component
public class UpstreamCredentials {

    private static final String PROPERTY = "skills-gateway.ingestion.upstream-credentials";

    private static final JDKHttpConnectionFactory DELEGATE = new JDKHttpConnectionFactory();

    private static final Pattern LOOPBACK_V4 = Pattern.compile("127(\\.\\d{1,3}){3}");

    private final List<Selected> entries;

    @Autowired
    public UpstreamCredentials(SkillsGatewayProperties properties) {
        this(properties.ingestion().upstreamCredentials());
    }

    UpstreamCredentials(List<UpstreamCredential> configured) {
        this(configured, Clock.systemUTC());
    }

    UpstreamCredentials(List<UpstreamCredential> configured, Clock clock) {
        this.entries = validated(configured, clock);
    }

    /**
     * Validates every entry; one that cannot work as written stops startup (GW_INGEST_0053,
     * GW_INGEST_0060).
     */
    @Requirements({"GW_INGEST_0053", "GW_INGEST_0060"})
    private static List<Selected> validated(List<UpstreamCredential> configured, Clock clock) {
        List<Selected> loaded = new ArrayList<>();
        for (int i = 0; i < configured.size(); i++) {
            Selected entry = load(i, configured.get(i), clock);
            for (Selected earlier : loaded) {
                if (earlier.prefix.equals(entry.prefix)) {
                    throw new IllegalStateException("%s[%d] declares the URL prefix '%s', which %s[%d] already declares"
                            .formatted(PROPERTY, entry.index, entry.urlPrefix, PROPERTY, earlier.index));
                }
            }
            loaded.add(entry);
        }
        return List.copyOf(loaded);
    }

    /** The credential whose prefix is the longest match for this upstream URL, if any (GW_INGEST_0050). */
    @Requirements({"GW_INGEST_0050"})
    public Optional<Selected> select(String url) {
        Target target = Target.of(url);
        if (target == null) {
            return Optional.empty();
        }
        return entries.stream()
                .filter(entry -> entry.prefix.covers(target))
                .max(Comparator.comparingInt(entry -> entry.prefix.path().length()));
    }

    /**
     * Every configured token and App key, and every installation token currently cached, for
     * scrubbing from anything that is recorded (GW_INGEST_0052, GW_AUTH_0049).
     */
    @Requirements({"GW_INGEST_0052", "GW_AUTH_0049"})
    public List<String> secrets() {
        List<String> secrets = new ArrayList<>();
        for (Selected entry : entries) {
            if (entry.app == null) {
                secrets.add(entry.token);
            } else {
                secrets.addAll(entry.app.secrets());
            }
        }
        return secrets;
    }

    private static Selected load(int index, UpstreamCredential credential, Clock clock) {
        String prefixText = credential.urlPrefix();
        String name = "%s[%d] (%s)".formatted(PROPERTY, index, prefixText);
        GitHubAppTokens app = null;
        if (credential.githubApp() == null) {
            if (credential.username() == null && credential.token() == null) {
                throw new IllegalStateException(
                        name + ": declares no credential; give it a username and token, or a github-app");
            }
            requireUsable(name, "username", credential.username());
            requireUsable(name, "token", credential.token());
        } else if (credential.username() != null || credential.token() != null) {
            // Exactly one kind per entry (GW_INGEST_0060): which one applies must not be a guess.
            throw new IllegalStateException(
                    name + ": declares both a username/token and a github-app; an entry is one kind or the other");
        } else {
            app = GitHubAppTokens.load(name, credential.githubApp(), clock);
        }
        Target prefix = Target.of(prefixText);
        if (prefix == null || prefix.query() != null || prefix.fragment() != null) {
            throw new IllegalStateException(name
                    + ": url-prefix must be an absolute http(s) URL with no userinfo, query, fragment, dot segments"
                    + " or encoded separators");
        }
        boolean cleartext = "http".equals(prefix.scheme());
        if (!"https".equals(prefix.scheme()) && !(cleartext && isLoopback(prefix.host()))) {
            throw new IllegalStateException(name
                    + ": url-prefix must use https; http is accepted only to a loopback host, where no network"
                    + " carries the token");
        }
        return new Selected(index, prefixText, prefix, credential.username(), credential.token(), app);
    }

    static void requireUsable(String name, String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("%s: %s is blank".formatted(name, field));
        }
        // An unresolvable ${...} reaches the binder as its own literal text.
        if (value.contains("${")) {
            throw new IllegalStateException(
                    "%s: %s still holds an unresolved ${...} reference; is its environment variable set?"
                            .formatted(name, field));
        }
    }

    static boolean isLoopback(String host) {
        return "localhost".equals(host)
                || "[::1]".equals(host)
                || LOOPBACK_V4.matcher(host).matches();
    }

    /**
     * What one upstream operation authenticates with: the {@code Authorization} value for requests
     * under the selected prefix, the secret inside it (for scrubbing), and whether it came from the
     * cache and may therefore be renewed once after a refusal (GW_INGEST_0058). {@link #NONE} for an
     * upstream under no prefix.
     */
    public record Access(Selected selected, String header, String secret, boolean renewable) {

        public static final Access NONE = new Access(null, null, null, false);

        @Override
        public String toString() {
            return "Access[" + (selected == null ? "anonymous" : selected.urlPrefix) + "]";
        }
    }

    /** One configured credential, as selected by a marketplace URL. */
    public static final class Selected {

        /** The Basic user GitHub expects with an installation token. */
        static final String APP_USER = "x-access-token";

        private final int index;
        private final String urlPrefix;
        private final Target prefix;
        private final String token;
        private final String header;
        private final GitHubAppTokens app;

        private Selected(
                int index, String urlPrefix, Target prefix, String username, String token, GitHubAppTokens app) {
            this.index = index;
            this.urlPrefix = urlPrefix;
            this.prefix = prefix;
            this.token = token;
            this.app = app;
            this.header = app == null ? basic(username, token) : null;
        }

        private static String basic(String username, String secret) {
            return "Basic "
                    + Base64.getEncoder().encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
        }

        /** The prefix as configured: what the ledger names (GW_INGEST_0055). */
        public String urlPrefix() {
            return urlPrefix;
        }

        /** Whether this entry mints GitHub App installation tokens rather than holding a token. */
        public boolean isGitHubApp() {
            return app != null;
        }

        /**
         * The credential one upstream operation on {@code url} uses (GW_INGEST_0056): the static
         * token, or an installation token for that repository — the cached one unless {@code renew}.
         * A token that cannot be minted leaves as an {@link UpstreamException} with its reason.
         */
        @Requirements({"GW_INGEST_0056", "GW_INGEST_0058"})
        public Access access(String url, boolean renew) {
            if (app == null) {
                return new Access(this, header, token, false);
            }
            GitHubAppTokens.Minted minted = app.token(url, renew);
            return new Access(this, basic(APP_USER, minted.token()), minted.token(), minted.cached());
        }

        /** Drops the cached installation token for {@code url}, after the upstream refused it. */
        public void forget(String url) {
            if (app != null) {
                app.forget(url);
            }
        }

        /** Whether this request may carry the credential (GW_INGEST_0051). */
        @Requirements({"GW_INGEST_0051"})
        boolean covers(URL url) {
            Target target;
            try {
                target = Target.of(url.toURI().toString());
            } catch (URISyntaxException e) {
                return false;
            }
            return target != null && prefix.covers(target);
        }

        /** A factory for one fetch or listing with the static credential; install it on that transport only. */
        HttpConnectionFactory connectionFactory() {
            return connectionFactory(header);
        }

        /** A factory for one fetch or listing that sends {@code authorization} under the prefix. */
        HttpConnectionFactory connectionFactory(String authorization) {
            return new Factory(authorization);
        }

        @Override
        public String toString() {
            return "UpstreamCredentials.Selected[" + urlPrefix + "]";
        }

        private final class Factory implements HttpConnectionFactory {

            private final String authorization;

            private Factory(String authorization) {
                this.authorization = authorization;
            }

            @Override
            public HttpConnection create(URL url) throws IOException {
                return create(url, null);
            }

            @Override
            @Requirements({"GW_INGEST_0051"})
            public HttpConnection create(URL url, Proxy proxy) throws IOException {
                HttpConnection connection = proxy == null ? DELEGATE.create(url) : DELEGATE.create(url, proxy);
                connection.setInstanceFollowRedirects(false);
                if (covers(url)) {
                    connection.setRequestProperty("Authorization", authorization);
                }
                return new Credentialed(connection);
            }
        }
    }

    /**
     * The gateway owns the {@code Authorization} header on an upstream request, and the JDK never
     * follows a redirect: every hop comes back through {@code create}, which decides again.
     */
    private static final class Credentialed extends ForwardingHttpConnection {

        private Credentialed(HttpConnection delegate) {
            super(delegate);
        }

        @Override
        public void setRequestProperty(String key, String value) {
            if (!"authorization".equalsIgnoreCase(key)) {
                delegate.setRequestProperty(key, value);
            }
        }

        @Override
        public void setInstanceFollowRedirects(boolean followRedirects) {
            delegate.setInstanceFollowRedirects(false);
        }
    }

    /**
     * An http(s) URL in the canonical form a credential may be matched against, or {@code null}.
     * A URL with userinfo, dot segments, encoded dots, slashes or backslashes has none: those are
     * the spellings that read as inside a prefix to a comparison and outside it to a server.
     */
    private record Target(String scheme, String host, int port, String path, String query, String fragment) {

        static Target of(String text) {
            if (text == null) {
                return null;
            }
            URI uri;
            try {
                uri = new URI(text);
            } catch (URISyntaxException e) {
                return null;
            }
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                return null;
            }
            String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
            String lowered = rawPath.toLowerCase(Locale.ROOT);
            if (uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || rawPath.contains("\\")
                    || lowered.contains("%2e")
                    || lowered.contains("%2f")
                    || lowered.contains("%5c")
                    || !uri.normalize().getRawPath().equals(rawPath)) {
                return null;
            }
            int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
            String path = rawPath;
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return new Target(
                    scheme,
                    uri.getHost().toLowerCase(Locale.ROOT),
                    port,
                    path,
                    uri.getRawQuery(),
                    uri.getRawFragment());
        }

        /** Same origin, and the path equal to this one's or below it by whole segments. */
        boolean covers(Target other) {
            return scheme.equals(other.scheme)
                    && host.equals(other.host)
                    && port == other.port
                    && (other.path.equals(path) || other.path.startsWith(path + "/"));
        }

        /** Equal as prefixes: the query and fragment are refused at load, so they never differ. */
        @Override
        public boolean equals(Object o) {
            return o instanceof Target t
                    && scheme.equals(t.scheme)
                    && host.equals(t.host)
                    && port == t.port
                    && path.equals(t.path);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(scheme, host, port, path);
        }
    }
}

package dev.skillsgateway.server.ingestion;

import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;

/**
 * Keeps every request of one upstream listing or fetch on the registered URL's scheme, host and port
 * (GW_INGEST_0075). JGit takes each redirect hop through {@code create}, so a hop elsewhere is
 * refused before anything connects to it; the JDK is never allowed to take a hop itself.
 */
final class UpstreamOriginFactory implements HttpConnectionFactory {

    private static final JDKHttpConnectionFactory JDK = new JDKHttpConnectionFactory();

    private final String registeredUrl;
    private final String scheme;
    private final String host;
    private final int port;
    private final String repositoryPath;
    private final HttpConnectionFactory credentialed;
    private final AtomicReference<String> movedTo;

    /**
     * {@code credentialed} attaches the selected credential, or is {@code null} for an anonymous
     * upstream; {@code movedTo} receives the first request that left the registered repository's path.
     */
    UpstreamOriginFactory(String registeredUrl, HttpConnectionFactory credentialed, AtomicReference<String> movedTo) {
        this.registeredUrl = registeredUrl;
        URL origin = parse(registeredUrl);
        this.scheme = origin == null ? null : origin.getProtocol().toLowerCase(Locale.ROOT);
        this.host = origin == null ? null : origin.getHost().toLowerCase(Locale.ROOT);
        this.port = origin == null ? -1 : effectivePort(origin);
        this.repositoryPath = origin == null ? null : repositoryPath(origin.getPath());
        this.credentialed = credentialed;
        this.movedTo = movedTo;
    }

    @Override
    public HttpConnection create(URL url) throws IOException {
        return create(url, null);
    }

    @Override
    @Requirements({"GW_INGEST_0075"})
    public HttpConnection create(URL url, Proxy proxy) throws IOException {
        if (scheme == null
                || !scheme.equals(url.getProtocol().toLowerCase(Locale.ROOT))
                || !host.equals(url.getHost().toLowerCase(Locale.ROOT))
                || port != effectivePort(url)) {
            throw new IOException("a request to '%s', which leaves the origin of the registered upstream '%s'"
                    .formatted(url, registeredUrl));
        }
        if (!withinRepository(url.getPath())) {
            // A same-origin hop JGit may take, which a forge answers for a transferred repository: the
            // content is someone else's, so what the registered URL says about it no longer holds.
            movedTo.compareAndSet(
                    null,
                    url.getProtocol() + "://" + url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort())
                            + url.getPath());
        }
        HttpConnection connection;
        if (credentialed != null) {
            connection = credentialed.create(url, proxy);
        } else {
            connection = proxy == null ? JDK.create(url) : JDK.create(url, proxy);
        }
        connection.setInstanceFollowRedirects(false);
        return new NoFollow(connection);
    }

    /** The registered path without a trailing separator or {@code .git}: the repository's name on the forge. */
    private static String repositoryPath(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.endsWith(".git") ? trimmed.substring(0, trimmed.length() - ".git".length()) : trimmed;
    }

    private boolean withinRepository(String path) {
        return path.equals(repositoryPath)
                || path.equals(repositoryPath + ".git")
                || path.startsWith(repositoryPath + "/")
                || path.startsWith(repositoryPath + ".git/");
    }

    private static URL parse(String url) {
        try {
            return URI.create(url).toURL();
        } catch (Exception e) {
            return null;
        }
    }

    private static int effectivePort(URL url) {
        return url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
    }

    private static final class NoFollow extends ForwardingHttpConnection {

        private NoFollow(HttpConnection delegate) {
            super(delegate);
        }

        @Override
        public void setInstanceFollowRedirects(boolean followRedirects) {
            delegate.setInstanceFollowRedirects(false);
        }
    }
}

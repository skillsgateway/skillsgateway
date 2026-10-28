package dev.skillsgateway.server.ingestion;

import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
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
    private final HttpConnectionFactory credentialed;

    /** {@code credentialed} attaches the selected credential, or is {@code null} for an anonymous upstream. */
    UpstreamOriginFactory(String registeredUrl, HttpConnectionFactory credentialed) {
        this.registeredUrl = registeredUrl;
        URL origin = parse(registeredUrl);
        this.scheme = origin == null ? null : origin.getProtocol().toLowerCase(Locale.ROOT);
        this.host = origin == null ? null : origin.getHost().toLowerCase(Locale.ROOT);
        this.port = origin == null ? -1 : effectivePort(origin);
        this.credentialed = credentialed;
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
        HttpConnection connection;
        if (credentialed != null) {
            connection = credentialed.create(url, proxy);
        } else {
            connection = proxy == null ? JDK.create(url) : JDK.create(url, proxy);
        }
        connection.setInstanceFollowRedirects(false);
        return new NoFollow(connection);
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

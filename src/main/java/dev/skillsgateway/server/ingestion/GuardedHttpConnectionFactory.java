package dev.skillsgateway.server.ingestion;

import io.github.reqstool.annotations.Requirements;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;

/**
 * The one place an external plugin source's fetch reaches the network (GW_0157, GW_0158).
 *
 * <p>Installed per fetch through {@code FetchCommand.setTransportConfigCallback} and
 * {@code TransportHttp.setHttpConnectionFactory} — deliberately <em>not</em> through
 * {@code HttpTransport.setConnectionFactory}, which is a JVM-wide static and would silently change
 * every other JGit HTTP transport in the process, including the marketplace upstream fetch this
 * change leaves alone. Scoping it means extending the policy to that path stays an explicit
 * decision rather than a side effect already taken.
 *
 * <p>One instance per source, because it carries per-fetch state: the origin every request is
 * pinned to, the redirect hop count, the bytes received so far, and the first refusal — which is
 * kept so the resolver can report <em>why</em> rather than surfacing a transport exception.
 *
 * <p>What it enforces, on every request and not only the first:
 *
 * <ul>
 *   <li>the URL policy — scheme, embedded credentials, ambiguous address literals;
 *   <li>the origin — same scheme, host and port as the source's clone URL, so a request can never
 *       be made anywhere else however the server answers;
 *   <li>the address policy, after resolution, against every address the host resolves to;
 *   <li>redirects: the {@code Location} is checked <em>before</em> the hop is taken, so a forbidden
 *       target is never contacted rather than being contacted and then rejected;
 *   <li>the received-byte budget, on the response stream, so an endless or explosive stream is cut
 *       off mid-transfer instead of being measured afterwards.
 * </ul>
 *
 * <p><b>Known limit, deliberately.</b> The connection is opened against the hostname, not against
 * the address that was validated, so a name that resolves differently between validation and
 * connect is not caught here. {@code HttpURLConnection} gives no hook to pin an address: the host
 * of an IP-literal URL would become the {@code Host} header, and overriding that header needs a
 * JVM-wide system property. It is a bounded gap in this increment because a {@code github} source's
 * host comes from {@code github-base-url} — operator configuration — and an {@code owner/repo}
 * shorthand cannot carry a host, so an attacker-influenced manifest cannot choose what is resolved.
 * Address pinning belongs with the increment that admits manifest-supplied URLs, and egress
 * isolation remains the primary control either way (ADR 0011 §3).
 */
final class GuardedHttpConnectionFactory implements HttpConnectionFactory {

    private static final JDKHttpConnectionFactory DELEGATE = new JDKHttpConnectionFactory();

    private final SourceUrlPolicy urlPolicy;
    private final SourceAddressPolicy addressPolicy;
    private final String originUrl;
    private final String originScheme;
    private final String originHost;
    private final int originPort;
    private final long maxReceivedBytes;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    private volatile String violation;
    private int redirects;
    private long receivedBytes;

    GuardedHttpConnectionFactory(
            SourceUrlPolicy urlPolicy,
            SourceAddressPolicy addressPolicy,
            String originUrl,
            long maxReceivedBytes,
            int connectTimeoutMillis,
            int readTimeoutMillis) {
        this.urlPolicy = urlPolicy;
        this.addressPolicy = addressPolicy;
        this.originUrl = originUrl;
        this.maxReceivedBytes = maxReceivedBytes;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        URL parsed = parse(originUrl);
        this.originScheme = parsed == null ? null : parsed.getProtocol().toLowerCase(Locale.ROOT);
        this.originHost = parsed == null ? null : parsed.getHost().toLowerCase(Locale.ROOT);
        this.originPort = parsed == null ? -1 : effectivePort(parsed);
    }

    /** The first refusal this factory made, or {@code null}. */
    String violation() {
        return violation;
    }

    /** Bytes received across every request of this fetch. */
    long receivedBytes() {
        return receivedBytes;
    }

    @Override
    public HttpConnection create(URL url) throws IOException {
        return create(url, null);
    }

    @Override
    @Requirements({"GW_0157", "GW_0158"})
    public HttpConnection create(URL url, Proxy proxy) throws IOException {
        String refusal = refuse(url);
        if (refusal != null) {
            throw refused(refusal);
        }
        HttpConnection connection = proxy == null ? DELEGATE.create(url) : DELEGATE.create(url, proxy);
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        // The JDK must not follow a redirect itself: a hop it takes is a hop no policy saw.
        connection.setInstanceFollowRedirects(false);
        return new Guarded(connection, url.toString());
    }

    /** Why this URL may not be requested, or {@code null}. Applied to every request. */
    private String refuse(URL url) {
        if (originScheme == null) {
            return "the source URL '%s' is not a URL the gateway can request".formatted(originUrl);
        }
        String target = url.toString();
        String policyRefusal = urlPolicy.refuseTarget(target);
        if (policyRefusal != null) {
            return policyRefusal;
        }
        String scheme = url.getProtocol().toLowerCase(Locale.ROOT);
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!originScheme.equals(scheme) || !originHost.equals(host) || originPort != effectivePort(url)) {
            return "a request to '%s', which is not the origin '%s' the source names".formatted(target, originUrl);
        }
        SourceAddressPolicy.Resolution resolution = addressPolicy.resolve(host);
        return resolution.permitted() ? null : resolution.violation();
    }

    private IOException refused(String reason) {
        if (violation == null) {
            violation = reason;
        }
        return new IOException(reason);
    }

    private static URL parse(String url) {
        try {
            return java.net.URI.create(url).toURL();
        } catch (Exception e) {
            return null;
        }
    }

    private static int effectivePort(URL url) {
        return url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
    }

    /**
     * The connection JGit is handed: the delegate's behaviour, with the redirect decision moved
     * ahead of the hop and the response stream bounded.
     */
    private final class Guarded implements HttpConnection {

        private final HttpConnection delegate;
        private final String requestUrl;

        private Guarded(HttpConnection delegate, String requestUrl) {
            this.delegate = delegate;
            this.requestUrl = requestUrl;
        }

        @Override
        public int getResponseCode() throws IOException {
            int code = delegate.getResponseCode();
            if (!isRedirect(code)) {
                return code;
            }
            String location = delegate.getHeaderField("Location");
            // Checked before the hop is taken, so a forbidden target is never contacted. Doing it
            // on the next create() would work too, but only after a request had already gone out.
            String refusal = urlPolicy.refuseRedirect(requestUrl, location, redirects + 1);
            if (refusal != null) {
                throw refused(refusal);
            }
            redirects++;
            return code;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new Bounded(delegate.getInputStream());
        }

        private static boolean isRedirect(int code) {
            return code == HttpConnection.HTTP_MOVED_PERM
                    || code == HttpConnection.HTTP_MOVED_TEMP
                    || code == HttpConnection.HTTP_SEE_OTHER
                    || code == HttpConnection.HTTP_11_MOVED_PERM
                    || code == HttpConnection.HTTP_11_MOVED_TEMP;
        }

        @Override
        public URL getURL() {
            return delegate.getURL();
        }

        @Override
        public String getResponseMessage() throws IOException {
            return delegate.getResponseMessage();
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            return delegate.getHeaderFields();
        }

        @Override
        public void setRequestProperty(String key, String value) {
            delegate.setRequestProperty(key, value);
        }

        @Override
        public void setRequestMethod(String method) throws java.net.ProtocolException {
            delegate.setRequestMethod(method);
        }

        @Override
        public void setUseCaches(boolean useCaches) {
            delegate.setUseCaches(useCaches);
        }

        @Override
        public void setConnectTimeout(int timeout) {
            delegate.setConnectTimeout(connectTimeoutMillis);
        }

        @Override
        public void setReadTimeout(int timeout) {
            delegate.setReadTimeout(readTimeoutMillis);
        }

        @Override
        public String getContentType() {
            return delegate.getContentType();
        }

        @Override
        public String getHeaderField(String name) {
            return delegate.getHeaderField(name);
        }

        @Override
        public List<String> getHeaderFields(String name) {
            return delegate.getHeaderFields(name);
        }

        @Override
        public int getContentLength() {
            return delegate.getContentLength();
        }

        @Override
        public void setInstanceFollowRedirects(boolean followRedirects) {
            // Never: a hop the JDK takes is a hop no policy saw.
            delegate.setInstanceFollowRedirects(false);
        }

        @Override
        public void setDoOutput(boolean dooutput) {
            delegate.setDoOutput(dooutput);
        }

        @Override
        public void setFixedLengthStreamingMode(int contentLength) {
            delegate.setFixedLengthStreamingMode(contentLength);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return delegate.getOutputStream();
        }

        @Override
        public void setChunkedStreamingMode(int chunklen) {
            delegate.setChunkedStreamingMode(chunklen);
        }

        @Override
        public String getRequestMethod() {
            return delegate.getRequestMethod();
        }

        @Override
        public boolean usingProxy() {
            return delegate.usingProxy();
        }

        @Override
        public void connect() throws IOException {
            delegate.connect();
        }

        @Override
        public void configure(KeyManager[] km, TrustManager[] tm, SecureRandom random)
                throws NoSuchAlgorithmException, KeyManagementException {
            delegate.configure(km, tm, random);
        }

        @Override
        public void setHostnameVerifier(HostnameVerifier hostnameverifier)
                throws NoSuchAlgorithmException, KeyManagementException {
            delegate.setHostnameVerifier(hostnameverifier);
        }
    }

    /**
     * The received-byte budget, enforced where the bytes are. Refusing after the transfer would
     * mean the bytes the budget exists to refuse had already been received, which for an endless
     * stream is the entire failure.
     */
    private final class Bounded extends FilterInputStream {

        private Bounded(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int bytes) throws IOException {
            receivedBytes += bytes;
            if (receivedBytes > maxReceivedBytes) {
                throw refused(
                        "the source sent more than the %d bytes permitted for one source".formatted(maxReceivedBytes));
            }
        }
    }
}

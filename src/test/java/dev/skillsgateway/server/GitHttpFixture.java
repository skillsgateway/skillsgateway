package dev.skillsgateway.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;

/**
 * An in-process git smart-HTTP server, so external plugin source resolution can be exercised
 * against a real transport without a container and without the public internet.
 *
 * <p>Built from the JDK's {@link HttpServer} and JGit's {@link UploadPack} — the same
 * {@code upload-pack} the gateway's own facade serves — rather than a stub, because the thing under
 * test is a JGit fetch over HTTP: a hand-written stub would exercise the test's idea of the
 * protocol instead of the protocol.
 *
 * <p>It is also the adversary. It can answer any request with a redirect, cut a transfer off
 * part-way, or stream more bytes than the gateway will accept; and it records every path it was
 * asked for, so a test can assert that a forbidden target was <em>never contacted</em> rather than
 * only that the ingestion failed.
 */
final class GitHttpFixture implements AutoCloseable {

    private final HttpServer server;
    private final Path root;
    private final Map<String, Repository> repositories = new ConcurrentHashMap<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();

    private volatile String redirectTo;
    private volatile int redirectsRemaining;
    private volatile String truncatePrefix;
    private volatile String floodPrefix;

    GitHttpFixture() throws IOException {
        this.root = Files.createTempDirectory(prepared(Path.of("target", "test-workdirs")), "forge");
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        this.server.start();
    }

    private static Path prepared(Path directory) throws IOException {
        Files.createDirectories(directory);
        return directory;
    }

    /** The base URL to point {@code github-base-url} at. Always loopback. */
    String baseUrl() {
        return "http://" + server.getAddress().getAddress().getHostAddress() + ":"
                + server.getAddress().getPort();
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Creates or updates {@code owner/repo} with the given files, and returns the shorthand. */
    String publish(String ownerRepo, Map<String, String> files) throws IOException, GitAPIException {
        Path directory = root.resolve(ownerRepo.replace('/', '-'));
        Repository existing = repositories.get(ownerRepo);
        if (existing == null) {
            Files.createDirectories(directory);
            try (Git git = Git.init()
                    .setDirectory(directory.toFile())
                    .setInitialBranch("main")
                    .call()) {
                repositories.put(ownerRepo, git.getRepository());
            }
        }
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = directory.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern(".").call();
            PersonIdent ident = new PersonIdent("Forge", "forge@example.com");
            git.commit()
                    .setMessage("publish " + ownerRepo + " " + System.nanoTime())
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .setSign(false)
                    .call();
        }
        return ownerRepo;
    }

    /** Every request from now on answers 302 to {@code target}, {@code times} times over. */
    void redirectTo(String target, int times) {
        this.redirectTo = target;
        this.redirectsRemaining = times;
    }

    /** Requests whose path starts with this get a truncated response: a transfer that dies. */
    void truncate(String pathPrefix) {
        this.truncatePrefix = pathPrefix;
    }

    /** Requests whose path starts with this get more bytes than any budget permits. */
    void flood(String pathPrefix) {
        this.floodPrefix = pathPrefix;
    }

    void reset() {
        redirectTo = null;
        redirectsRemaining = 0;
        truncatePrefix = null;
        floodPrefix = null;
        requested.clear();
    }

    /** Every path this server was asked for, in order. */
    List<String> requestedPaths() {
        return List.copyOf(requested);
    }

    boolean wasRequested(String path) {
        return requested.stream().anyMatch(seen -> seen.equals(path));
    }

    @Override
    public void close() {
        repositories.values().forEach(Repository::close);
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requested.add(path);
        try {
            if (redirectsRemaining > 0 && redirectTo != null) {
                redirectsRemaining--;
                exchange.getResponseHeaders().add("Location", redirectTo);
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            if (floodPrefix != null && path.startsWith(floodPrefix)) {
                flood(exchange);
                return;
            }
            if (truncatePrefix != null && path.startsWith(truncatePrefix)) {
                truncated(exchange);
                return;
            }
            Repository repository = repositoryFor(path);
            if (repository == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (path.endsWith("/info/refs")) {
                advertise(exchange, repository);
            } else if (path.endsWith("/git-upload-pack")) {
                uploadPack(exchange, repository);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        } catch (RuntimeException | IOException e) {
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
                // The client has already gone; there is nothing to tell it.
            }
        } finally {
            exchange.close();
        }
    }

    private Repository repositoryFor(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        for (String suffix : new String[] {"/info/refs", "/git-upload-pack"}) {
            if (trimmed.endsWith(suffix)) {
                trimmed = trimmed.substring(0, trimmed.length() - suffix.length());
            }
        }
        return repositories.get(trimmed);
    }

    private void advertise(HttpExchange exchange, Repository repository) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        PacketLineOut packets = new PacketLineOut(buffer);
        packets.writeString("# service=git-upload-pack\n");
        packets.end();
        try (UploadPack upload = new UploadPack(repository)) {
            upload.setBiDirectionalPipe(false);
            upload.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(packets));
        }
        byte[] body = buffer.toByteArray();
        exchange.getResponseHeaders().add("Content-Type", "application/x-git-upload-pack-advertisement");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    private void uploadPack(HttpExchange exchange, Repository repository) throws IOException {
        byte[] request = requestBody(exchange);
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (UploadPack upload = new UploadPack(repository)) {
            upload.setBiDirectionalPipe(false);
            upload.upload(new java.io.ByteArrayInputStream(request), buffer, null);
        }
        byte[] body = buffer.toByteArray();
        exchange.getResponseHeaders().add("Content-Type", "application/x-git-upload-pack-result");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    /**
     * A protocol-correct reference advertisement that simply never stops — megabytes of valid
     * pkt-lines. Deliberately valid rather than junk: junk makes the client fail on a parse error,
     * which proves nothing about a byte budget. This is the shape the received-byte budget exists
     * to cut off, and the only thing wrong with it is its size.
     */
    private static void flood(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/x-git-upload-pack-advertisement");
        exchange.sendResponseHeaders(200, 0);
        String sha = "1".repeat(40);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(packet("# service=git-upload-pack\n"));
            out.write("0000".getBytes(StandardCharsets.UTF_8));
            out.write(packet(sha + " refs/heads/main side-band-64k\n"));
            for (int line = 0; line < 60_000; line++) {
                out.write(packet(sha + " refs/heads/flood-" + line + "\n"));
                if (line % 256 == 0) {
                    out.flush();
                }
            }
            out.write("0000".getBytes(StandardCharsets.UTF_8));
        } catch (IOException expected) {
            // The gateway cut the transfer off, which is the point of the test.
        }
    }

    private static byte[] packet(String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        return ("%04x".formatted(body.length + 4) + payload).getBytes(StandardCharsets.UTF_8);
    }

    /** A response that starts and then stops: a transfer that dies part-way through. */
    private static void truncated(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/x-git-upload-pack-advertisement");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write("001e# service=git-upload-pack\n0000".getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();
    }

    private static byte[] requestBody(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        if ("gzip".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
            in = new GZIPInputStream(in);
        }
        return in.readAllBytes();
    }
}

package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;

/** The translator alone (GW_INGEST_0038): what each upstream failure reads as, away from any transport. */
class UpstreamFailureTests {

    private static final URIish URI = uri("https://forge.example/acme/skills.git");

    @Test
    @SVCs({"SVC_GW_INGEST_0038"})
    void not_found_and_every_authentication_refusal_read_the_same() {
        Throwable notFound = new InvalidRemoteException(
                "Invalid remote: origin", new NoRemoteRepositoryException(URI, "https://forge.example not found"));
        Throwable noCredentials = jgitTransport(JGitText.get().noCredentialsProvider);
        Throwable notAuthorized = jgitTransport(JGitText.get().notAuthorized);
        Throwable unsupported = jgitTransport(JGitText.get().authenticationNotSupported);
        Throwable forbidden =
                jgitTransport("git-upload-pack not permitted on 'https://forge.example/acme/skills.git/'");

        for (Throwable failure : new Throwable[] {notFound, noCredentials, notAuthorized, unsupported, forbidden}) {
            assertThat(UpstreamFailure.of(failure).reason())
                    .as("%s", failure)
                    .isEqualTo(UpstreamFailure.NOT_FOUND_OR_AUTH);
            assertThat(UpstreamFailure.of(failure).nextStep()).isNotBlank();
        }
        // The root cause is the innermost message, not the "Invalid remote: origin" wrapper.
        assertThat(UpstreamFailure.of(notFound).rootCause()).contains("not found");
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0038"})
    void network_failures_are_told_apart() {
        assertThat(UpstreamFailure.of(wrapped(new UnknownHostException("forge.invalid")))
                        .reason())
                .isEqualTo(UpstreamFailure.HOST_UNKNOWN);
        assertThat(UpstreamFailure.of(wrapped(new ConnectException("Connection refused")))
                        .reason())
                .isEqualTo(UpstreamFailure.UNREACHABLE);
        assertThat(UpstreamFailure.of(wrapped(new SocketTimeoutException("Read timed out")))
                        .reason())
                .isEqualTo(UpstreamFailure.UNREACHABLE);
        assertThat(UpstreamFailure.of(wrapped(new SSLHandshakeException("PKIX path building failed")))
                        .reason())
                .isEqualTo(UpstreamFailure.TLS);
        assertThat(UpstreamFailure.of(new IllegalStateException("something else"))
                        .reason())
                .isEqualTo(UpstreamFailure.OTHER);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0038"})
    void a_specific_first_error_attached_to_a_generic_retry_decides() {
        Throwable first = jgitTransport(JGitText.get().noCredentialsProvider);
        Throwable retry = new IllegalStateException("remote hung up unexpectedly");
        retry.addSuppressed(first);

        UpstreamFailure failure = UpstreamFailure.of(retry);

        assertThat(failure.reason()).isEqualTo(UpstreamFailure.NOT_FOUND_OR_AUTH);
        assertThat(failure.rootCause()).contains(JGitText.get().noCredentialsProvider);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0038"})
    void a_credential_in_a_url_is_never_repeated() {
        Throwable failure = new ConnectException(
                "connect to http://alice:s3cret@forge.example/x.git and https://tok3n@forge.example/y failed");

        UpstreamFailure translated = UpstreamFailure.of(failure);

        assertThat(translated.rootCause()).doesNotContain("s3cret").doesNotContain("tok3n");
        assertThat(translated.describe()).doesNotContain("s3cret").doesNotContain("tok3n");
        assertThat(translated.rootCause()).contains("http://***@forge.example/x.git");
    }

    @Test
    void describe_names_reason_root_cause_and_next_step() {
        UpstreamFailure failure = UpstreamFailure.of(wrapped(new ConnectException("Connection refused")));

        assertThat(failure.describe())
                .contains(failure.reason())
                .contains("Connection refused")
                .contains(failure.nextStep());
    }

    private static Throwable jgitTransport(String message) {
        return new org.eclipse.jgit.api.errors.TransportException(
                URI + ": " + message, new org.eclipse.jgit.errors.TransportException(URI, message));
    }

    private static Throwable wrapped(Throwable cause) {
        return new org.eclipse.jgit.api.errors.TransportException(
                "cannot open git-upload-pack", new org.eclipse.jgit.errors.TransportException(URI, "failed", cause));
    }

    private static URIish uri(String value) {
        try {
            return new URIish(value);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }
}

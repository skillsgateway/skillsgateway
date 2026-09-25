package dev.skillsgateway.server.ingestion;

import io.github.reqstool.annotations.Requirements;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.internal.JGitText;

/**
 * Why an upstream could not be read, in the words an operator acts on (GW_INGEST_0038): a
 * plain-language reason, the root cause, and a next step. The one translator for ingestion and
 * registration, so the two never describe the same failure differently.
 *
 * @param reason what went wrong, from a small fixed vocabulary
 * @param nextStep what to check or do about it
 * @param rootCause the innermost message of the error that decided the reason, credentials redacted
 */
public record UpstreamFailure(String reason, String nextStep, String rootCause) {

    /**
     * Deliberately one reason: a forge commonly answers 401 for a repository that does not exist,
     * so that private repositories cannot be enumerated, and saying only "authentication" sends the
     * operator after credentials for a URL that is simply wrong.
     */
    public static final String NOT_FOUND_OR_AUTH = "repository not found or requires authentication";

    public static final String HOST_UNKNOWN = "the upstream host could not be resolved";
    public static final String UNREACHABLE = "the upstream could not be reached";
    public static final String TLS = "the TLS connection to the upstream failed";
    public static final String NO_DEFAULT_BRANCH = "the upstream has no default branch";
    public static final String OTHER = "the upstream fetch failed";

    /** Userinfo in a URL: {@code scheme://user:secret@}. Never repeated anywhere a failure is recorded. */
    private static final Pattern USERINFO = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@");

    /** JGit's own wording for an answer that means not found, unauthorised or forbidden. */
    private static final List<String> REFUSAL_TEXTS = List.of(
            JGitText.get().notAuthorized,
            JGitText.get().noCredentialsProvider,
            JGitText.get().authenticationNotSupported,
            " not permitted on ");

    /**
     * Translates a failure. The cause chain <em>and</em> every suppressed error are consulted, so a
     * retry's generic error cannot hide the specific one attached to it; the first class to match
     * anywhere, in the order below, wins, and its root cause is the matching error's.
     */
    @Requirements({"GW_INGEST_0038"})
    public static UpstreamFailure of(Throwable failure) {
        List<Throwable> all = flatten(failure);
        for (Throwable candidate : all) {
            if (candidate instanceof NoRemoteRepositoryException || refusalText(candidate)) {
                return new UpstreamFailure(
                        NOT_FOUND_OR_AUTH,
                        "Check the clone URL for typos; if the repository is private, configure an upstream"
                                + " credential for its URL prefix whose token can read it.",
                        rootCause(candidate));
            }
        }
        for (Throwable candidate : all) {
            if (candidate instanceof UnknownHostException) {
                return new UpstreamFailure(
                        HOST_UNKNOWN,
                        "Check the host name in the clone URL, and that the gateway can resolve it.",
                        rootCause(candidate));
            }
        }
        for (Throwable candidate : all) {
            if (candidate instanceof ConnectException
                    || candidate instanceof NoRouteToHostException
                    || candidate instanceof SocketTimeoutException) {
                return new UpstreamFailure(
                        UNREACHABLE,
                        "Check that the upstream is up and that the gateway's network (proxy, firewall,"
                                + " egress) lets it through.",
                        rootCause(candidate));
            }
        }
        for (Throwable candidate : all) {
            if (candidate instanceof SSLException) {
                return new UpstreamFailure(
                        TLS,
                        "Check that the upstream's certificate is trusted by the gateway's Java trust store.",
                        rootCause(candidate));
            }
        }
        return new UpstreamFailure(
                OTHER, "Check the root cause, correct the upstream, and try again.", rootCause(failure));
    }

    /** The upstream answered, but has no default branch for the gateway to pin (GW_INGEST_0006). */
    public static UpstreamFailure noDefaultBranch(String detail) {
        return new UpstreamFailure(
                NO_DEFAULT_BRANCH, "Push a commit to the upstream's default branch, then try again.", redact(detail));
    }

    /** A failure that is not the upstream's answer (storage, vetting, a hosted origin never pushed). */
    public static UpstreamFailure unclassified(Throwable failure) {
        return new UpstreamFailure(
                "the ingestion could not complete",
                "Check the root cause; the gateway log has the full error.",
                rootCause(failure));
    }

    /**
     * This failure with every one of {@code secrets} replaced by {@code ***} (GW_INGEST_0052). JGit
     * cannot quote an upstream credential, which is in no URL, but a server's own message can.
     */
    @Requirements({"GW_INGEST_0052"})
    public UpstreamFailure scrub(Collection<String> secrets) {
        return new UpstreamFailure(scrub(reason, secrets), scrub(nextStep, secrets), scrub(rootCause, secrets));
    }

    private static String scrub(String text, Collection<String> secrets) {
        String out = text;
        for (String secret : secrets) {
            if (out != null && secret != null && !secret.isEmpty()) {
                out = out.replace(secret, "***");
            }
        }
        return out;
    }

    /** One line for the marketplace record, the ledger and the log. */
    public String describe() {
        return "%s (%s). %s".formatted(reason, rootCause, nextStep);
    }

    static String redact(String text) {
        return text == null ? null : USERINFO.matcher(text).replaceAll("$1***@");
    }

    private static boolean refusalText(Throwable candidate) {
        String message = candidate.getMessage();
        return message != null && REFUSAL_TEXTS.stream().anyMatch(message::contains);
    }

    /** The innermost message along the cause chain of this one error, redacted. */
    private static String rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return redact(message == null || message.isBlank() ? root.getClass().getSimpleName() : message);
    }

    /** Depth-first over causes and suppressed errors, each visited once. */
    private static List<Throwable> flatten(Throwable failure) {
        List<Throwable> out = new ArrayList<>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        List<Throwable> stack = new ArrayList<>(List.of(failure));
        while (!stack.isEmpty()) {
            Throwable current = stack.removeLast();
            if (current == null || seen.put(current, Boolean.TRUE) != null) {
                continue;
            }
            out.add(current);
            for (Throwable suppressed : current.getSuppressed()) {
                stack.add(suppressed);
            }
            stack.add(current.getCause());
        }
        return out;
    }
}

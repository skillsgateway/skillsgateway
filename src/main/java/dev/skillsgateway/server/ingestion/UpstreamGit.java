package dev.skillsgateway.server.ingestion;

import dev.skillsgateway.server.ingestion.UpstreamCredentials.Access;
import dev.skillsgateway.server.ingestion.UpstreamCredentials.Selected;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TransportHttp;
import org.springframework.stereotype.Component;

/**
 * The one door to a marketplace upstream: registration lists it through here and ingestion fetches
 * it through here (GW_INGEST_0040), so the two can never take different routes to the network.
 * Every failure leaves as an {@link UpstreamException} carrying its translation (GW_INGEST_0038).
 */
@Component
public class UpstreamGit {

    /** Idle, not total: a large fetch that keeps moving is never cut off; a silent upstream is. */
    static final int TIMEOUT_SECONDS = 60;

    private final UpstreamCredentials credentials;

    public UpstreamGit(UpstreamCredentials credentials) {
        this.credentials = credentials;
    }

    /** The default branch registration resolved: the ref the gateway pins (GW_INGEST_0006). */
    public record Probe(String defaultBranch, ObjectId head) {}

    /** Lists the upstream and resolves its default branch, or says why it cannot. */
    @Requirements({"GW_INGEST_0040", "GW_INGEST_0038"})
    public Probe probe(String url) {
        Map<String, Ref> refs = read(url, access -> listRefs(url, access));
        String branch = defaultBranch(url, refs);
        return new Probe(branch, refs.get(Constants.HEAD).getObjectId());
    }

    /**
     * Fetches the upstream's default branch to {@code targetRef}. The HEAD refspec first; some
     * transports reject it, so on failure the default branch is resolved by listing and fetched
     * by name — and when that fails too, the first error stays attached to the second.
     */
    @Requirements({"GW_INGEST_0002", "GW_INGEST_0038"})
    public ObjectId fetchDefaultBranch(Repository repo, String url, String targetRef) {
        try (Git git = new Git(repo)) {
            read(url, access -> {
                try {
                    connect(git.fetch().setRemote(url).setRefSpecs(new RefSpec("+HEAD:" + targetRef)), access)
                            .call();
                } catch (GitAPIException | RuntimeException first) {
                    try {
                        String branch = defaultBranch(url, listRefs(url, access));
                        connect(
                                        git.fetch()
                                                .setRemote(url)
                                                .setRefSpecs(new RefSpec("+" + branch + ":" + targetRef)),
                                        access)
                                .call();
                    } catch (GitAPIException | RuntimeException second) {
                        second.addSuppressed(first);
                        throw second;
                    }
                }
                return null;
            });
            ObjectId sha = repo.resolve(targetRef);
            if (sha == null) {
                throw new UpstreamException(UpstreamFailure.noDefaultBranch("the fetch produced no commit"), null);
            }
            return sha;
        } catch (IOException e) {
            throw new UpstreamException(translate(e, Access.NONE), e);
        }
    }

    /** One upstream operation, given the credential it runs with. */
    @FunctionalInterface
    private interface Operation<T> {
        T run(Access access) throws GitAPIException;
    }

    /**
     * Runs one upstream operation with the credential its URL selects, and translates its failure.
     * A GitHub App token taken from the cache that the upstream refuses is dropped and minted again,
     * and the operation retried once — never more (GW_INGEST_0058).
     */
    @Requirements({"GW_INGEST_0050", "GW_INGEST_0056", "GW_INGEST_0058"})
    private <T> T read(String url, Operation<T> operation) {
        Optional<Selected> selected = credentials.select(url);
        Access access = selected.map(entry -> entry.access(url, false)).orElse(Access.NONE);
        try {
            return operation.run(access);
        } catch (GitAPIException | RuntimeException first) {
            UpstreamFailure failure = failureOf(first, access);
            if (selected.isEmpty() || !selected.get().isGitHubApp() || !refused(failure)) {
                throw asUpstream(first, failure);
            }
            if (!access.renewable()) {
                selected.get().forget(url);
                throw asUpstream(first, failure);
            }
            // Renewing drops the refused token itself, and scrubs it from a failure to mint.
            Access renewed = selected.get().access(url, true);
            try {
                return operation.run(renewed);
            } catch (GitAPIException | RuntimeException second) {
                second.addSuppressed(first);
                UpstreamFailure again = failureOf(second, renewed, access);
                if (refused(again)) {
                    selected.get().forget(url);
                }
                throw asUpstream(second, again);
            }
        }
    }

    private static boolean refused(UpstreamFailure failure) {
        return UpstreamFailure.NOT_FOUND_OR_AUTH.equals(failure.reason());
    }

    private UpstreamFailure failureOf(Throwable failure, Access... accesses) {
        if (failure instanceof UpstreamException upstream && upstream.failure() != null) {
            return upstream.failure();
        }
        return translate(failure, accesses);
    }

    private static UpstreamException asUpstream(Throwable failure, UpstreamFailure translated) {
        return failure instanceof UpstreamException upstream ? upstream : new UpstreamException(translated, failure);
    }

    private Map<String, Ref> listRefs(String url, Access access) throws GitAPIException {
        return connect(Git.lsRemoteRepository().setRemote(url), access).callAsMap();
    }

    /**
     * Where every upstream command is configured before it runs, so registration's listing and
     * ingestion's fetch get the same credential (GW_INGEST_0050). The credential is chosen once, from
     * the marketplace URL, and rides only on requests under its own prefix (GW_INGEST_0051). An
     * upstream under no prefix gets no factory at all: JGit's own transport, anonymous.
     */
    @Requirements({"GW_INGEST_0050", "GW_INGEST_0051"})
    private static <C extends TransportCommand<C, ?>> C connect(C command, Access access) {
        command.setTimeout(TIMEOUT_SECONDS);
        if (access.selected() != null) {
            command.setTransportConfigCallback(transport -> {
                if (transport instanceof TransportHttp http) {
                    http.setHttpConnectionFactory(access.selected().connectionFactory(access.header()));
                }
            });
        }
        return command;
    }

    /**
     * The translation, with every configured secret and this operation's own token scrubbed from it
     * (GW_INGEST_0052, GW_AUTH_0049).
     */
    private UpstreamFailure translate(Throwable failure, Access... accesses) {
        List<String> secrets = new ArrayList<>(credentials.secrets());
        for (Access access : accesses) {
            if (access.secret() != null) {
                secrets.add(access.secret());
            }
        }
        return UpstreamFailure.of(failure).scrub(secrets);
    }

    private static String defaultBranch(String url, Map<String, Ref> refs) {
        Ref head = refs.get(Constants.HEAD);
        if (head == null || head.getObjectId() == null) {
            throw new UpstreamException(UpstreamFailure.noDefaultBranch("no HEAD advertised by the upstream"), null);
        }
        if (head.isSymbolic()) {
            return head.getTarget().getName();
        }
        ObjectId id = head.getObjectId();
        for (String preferred : List.of("refs/heads/main", "refs/heads/master")) {
            Ref candidate = refs.get(preferred);
            if (candidate != null && id.equals(candidate.getObjectId())) {
                return preferred;
            }
        }
        return refs.entrySet().stream()
                .filter(e -> e.getKey().startsWith(Constants.R_HEADS)
                        && id.equals(e.getValue().getObjectId()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new UpstreamException(
                        UpstreamFailure.noDefaultBranch("HEAD matches no branch of " + UpstreamFailure.redact(url)),
                        null));
    }
}

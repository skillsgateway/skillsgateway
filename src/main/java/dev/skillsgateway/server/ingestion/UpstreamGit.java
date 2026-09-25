package dev.skillsgateway.server.ingestion;

import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
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

    /** The default branch registration resolved: the ref the gateway pins (GW_INGEST_0006). */
    public record Probe(String defaultBranch, ObjectId head) {}

    /** Lists the upstream and resolves its default branch, or says why it cannot. */
    @Requirements({"GW_INGEST_0040", "GW_INGEST_0038"})
    public Probe probe(String url) {
        Map<String, Ref> refs;
        try {
            refs = listRefs(url);
        } catch (GitAPIException | RuntimeException e) {
            throw new UpstreamException(UpstreamFailure.of(e), e);
        }
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
            try {
                connect(git.fetch().setRemote(url).setRefSpecs(new RefSpec("+HEAD:" + targetRef)))
                        .call();
            } catch (GitAPIException | RuntimeException first) {
                try {
                    String branch = defaultBranch(url, listRefs(url));
                    connect(git.fetch().setRemote(url).setRefSpecs(new RefSpec("+" + branch + ":" + targetRef)))
                            .call();
                } catch (UpstreamException second) {
                    second.addSuppressed(first);
                    throw second;
                } catch (GitAPIException | RuntimeException second) {
                    second.addSuppressed(first);
                    throw new UpstreamException(UpstreamFailure.of(second), second);
                }
            }
            ObjectId sha = repo.resolve(targetRef);
            if (sha == null) {
                throw new UpstreamException(UpstreamFailure.noDefaultBranch("the fetch produced no commit"), null);
            }
            return sha;
        } catch (IOException e) {
            throw new UpstreamException(UpstreamFailure.of(e), e);
        }
    }

    private Map<String, Ref> listRefs(String url) throws GitAPIException {
        return connect(Git.lsRemoteRepository().setRemote(url)).callAsMap();
    }

    /**
     * Where every upstream command is configured before it runs. Upstream credentials (#494) attach
     * here, so registration's listing and ingestion's fetch get them at once.
     */
    private static <C extends TransportCommand<C, ?>> C connect(C command) {
        return command.setTimeout(TIMEOUT_SECONDS);
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

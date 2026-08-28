package dev.skillsgateway.server.storage;

import java.io.IOException;
import java.util.Optional;
import org.eclipse.jgit.lib.Repository;

/**
 * The storage seam between the gateway and its git repositories.
 *
 * <p>Quarantine holds everything ingested and is never served; published holds only approved
 * content and is all the consumer facade ever opens; a hosted marketplace additionally has an
 * origin repository that publishers push to and only ingestion reads. A JGit-DFS implementation over object storage
 * replaces this interface on the roadmap (ARCHITECTURE.md §12) without touching callers.
 *
 * <p>Returned repositories are open handles; callers close them (try-with-resources).
 */
public interface GitStorage {

    /** Open (creating if absent) the quarantine repository for a marketplace. */
    Repository quarantine(String marketplace) throws IOException;

    /**
     * Open (creating if absent) the origin repository of a gateway-hosted marketplace (GW_0101):
     * the publisher's source of record, which is neither quarantine nor published. Ingestion
     * fetches out of it exactly as it fetches from an upstream URL, so quarantine keeps its
     * property of having exactly one writer.
     */
    Repository hosted(String marketplace) throws IOException;

    /** Open the origin repository only if it already exists; empty otherwise. */
    Optional<Repository> hostedIfPresent(String marketplace) throws IOException;

    /** Open (creating if absent) the published repository for a marketplace. */
    Repository published(String marketplace) throws IOException;

    /** Open the published repository only if it exists and has served content. */
    Optional<Repository> publishedIfServing(String marketplace) throws IOException;

    /**
     * The exact inverse of publication (GW_0050): removes every published ref through which one
     * snapshot's content is reachable, and nothing else.
     *
     * <p>Two refs put a snapshot on the wire, and both must go. {@code refs/heads/main} is the
     * served tip — removed only when it is still this snapshot, so revoking a snapshot a later
     * approval has already superseded does not take the marketplace down with it. And
     * {@code refs/snapshots/<sha>}, which approval copies alongside main, is advertised by
     * upload-pack in its own right: leaving it behind would keep the revoked commit fetchable by
     * name after the marketplace stopped resolving at all.
     *
     * <p>Nothing is re-ingested and quarantine is untouched: the content is still there to be
     * re-reviewed, it has merely stopped being served.
     *
     * @return true when this call is what stopped the marketplace serving — i.e. the removed ref
     *     was the tip — so the caller can say so in the ledger without re-deriving it
     */
    boolean unpublish(String marketplace, String sha) throws IOException;
}

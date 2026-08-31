package dev.skillsgateway.server.storage;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * The storage seam between the gateway and its git repositories.
 *
 * <p>Quarantine holds everything ingested and is never served; published holds only approved
 * content and is all the consumer facade ever opens; a hosted marketplace additionally has an
 * origin repository that publishers push to and only ingestion reads.
 *
 * <p>There are two implementations and exactly one of them is in the context, chosen by
 * {@code skills-gateway.storage.backend}: bare repositories on a filesystem, or JGit DFS over an
 * S3-compatible bucket. No caller knows which answered, which is the point of the seam — and it is
 * what {@code GitStorageContractTests} exists to keep true, since a difference between the two
 * would be invisible above this interface.
 *
 * <p>Returned repositories are open handles; callers close them (try-with-resources).
 */
public interface GitStorage {

    /**
     * Where publication puts a snapshot's objects before it commits to serving them.
     *
     * <p>Deliberately outside the advertised namespaces, so a transfer that completes and a
     * transition that is then refused leaves nothing on the wire. A staging reference left behind by
     * a crash serves nothing and is overwritten by the next publication of the same snapshot.
     */
    String STAGING_REF_PREFIX = "refs/staging/";

    /**
     * The three repository roles, named so that a caller can talk about all of them at once.
     *
     * <p>Only migration needs this: everything else in the gateway knows exactly which role it
     * wants and asks for it by name, which is what keeps "quarantine is never served" a property
     * of the call sites rather than of a parameter.
     */
    enum Role {
        QUARANTINE,
        HOSTED,
        PUBLISHED;

        /** The role's own name in the layout, lowercase — a directory here, a key prefix there. */
        public String path() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Every marketplace that already has a repository in this role, as the backend itself sees it.
     *
     * <p>Read off the storage rather than off the database on purpose: a migration copies what is
     * actually there, so a repository the database has forgotten still moves, and a marketplace
     * row with nothing on disk does not invent an empty repository at the destination.
     */
    Set<String> marketplaces(Role role) throws IOException;

    /** Open (creating if absent) the repository a role holds for a marketplace. */
    default Repository open(Role role, String marketplace) throws IOException {
        return switch (role) {
            case QUARANTINE -> quarantine(marketplace);
            case HOSTED -> hosted(marketplace);
            case PUBLISHED -> published(marketplace);
        };
    }

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

    /**
     * Puts one snapshot's content on the wire (GW_0132) — the exact inverse of
     * {@link #unpublish(String, String)}, and the only way content becomes served.
     *
     * <p>Publication was for a long time the one reference transition performed <em>outside</em>
     * this seam: {@code ApprovalService} took a raw published repository and did its own fetch and
     * {@code RefUpdate}, so putting a snapshot on the wire was two unsynchronised writes while
     * taking the same pair off was one atomic transaction here. That asymmetry is what let a refused
     * update leave {@code refs/snapshots/<sha>} advertised with {@code refs/heads/main} unmoved —
     * a snapshot fetchable by name that the marketplace does not resolve to.
     *
     * <p>So the two references land together or not at all. Objects are copied first, into an
     * unadvertised staging reference; only then do the served references move, and a refusal at that
     * point leaves nothing published. What staging holds is not served: the facade advertises
     * {@code refs/heads/main} and {@code refs/snapshots/*} and nothing else.
     *
     * @return true when this call is what <em>started</em> the marketplace serving — the mirror of
     *     {@code unpublish}'s return, so the caller can say so on the ledger without re-deriving it
     */
    default boolean publish(String marketplace, String sha) throws IOException {
        ObjectId tip = ObjectId.fromString(sha);
        try (Repository quarantine = quarantine(marketplace);
                Repository published = published(marketplace)) {
            GitObjectTransfer.copy(quarantine, published, tip);
            RefTransitions.write(published, STAGING_REF_PREFIX + sha, tip);
        }
        return commitPublication(marketplace, sha);
    }

    /**
     * Moves the served references onto a snapshot whose objects are already staged, as one
     * all-or-nothing transition, and drops the staging reference.
     *
     * <p>Split from {@link #publish(String, String)} so object transfer — which is identical
     * everywhere and needs no backend knowledge — is written once, while the transition, which is
     * the part that has to be atomic and is the part a backend can actually make atomic, belongs to
     * the backend.
     *
     * @return true when this call is what started the marketplace serving
     */
    boolean commitPublication(String marketplace, String sha) throws IOException;
}

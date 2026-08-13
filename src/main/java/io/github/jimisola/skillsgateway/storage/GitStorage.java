package io.github.jimisola.skillsgateway.storage;

import java.io.IOException;
import java.util.Optional;
import org.eclipse.jgit.lib.Repository;

/**
 * The storage seam between the gateway and its git repositories.
 *
 * <p>Quarantine holds everything fetched from upstreams and is never served; published holds only
 * approved content and is all the facade ever opens. A JGit-DFS implementation over object storage
 * replaces this interface on the roadmap (ARCHITECTURE.md §12) without touching callers.
 *
 * <p>Returned repositories are open handles; callers close them (try-with-resources).
 */
public interface GitStorage {

    /** Open (creating if absent) the quarantine repository for a marketplace. */
    Repository quarantine(String marketplace) throws IOException;

    /** Open (creating if absent) the published repository for a marketplace. */
    Repository published(String marketplace) throws IOException;

    /** Open the published repository only if it exists and has served content. */
    Optional<Repository> publishedIfServing(String marketplace) throws IOException;
}

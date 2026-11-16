package dev.skillsgateway.server.storage;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceName;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreGitStorage;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * TEMPORARY. Lists the repositories the removed hosted-marketplace feature left behind, so an
 * administrator can be told where the only copy of each publisher's source is (GW_AUTH_0091).
 *
 * <p><b>REMOVE-AFTER: two minor releases after the release that ships migration
 * V3__remove_hosted_marketplaces.</b> When that condition holds, delete this class,
 * {@code FormerHostedRepositoriesController}, the two {@code formerHosted*} methods of the
 * object-store storage, the route's entry in {@code MachineApiRegistry}, the Overview banner, the
 * requirement GW_AUTH_0091 with its verification cases, and the guide's paragraph about the notice.
 * Search for the condition above to find every piece.
 *
 * <p>It stores nothing and the list is read from storage on each call, so it ends when the folders
 * do. It never opens anything for write and never deletes: the operator copies and deletes the
 * folders themselves.
 *
 * <p>Filesystem backend: only the direct children of {@code {data-dir}/hosted} that are directories
 * and not symbolic links, named {@code <marketplace-name>.git} under the marketplace name rule. A
 * stray file, a link (never followed out of the folder), a differently named directory and
 * anything deeper is ignored. The last commit is read through JGit and any failure to read it only
 * leaves it out. Object-store backend: names and key prefix only, because the role that opened
 * those repositories no longer exists and the last commit is not worth rebuilding it for.
 */
@Component
@Requirements({"GW_AUTH_0091"})
public class FormerHostedRepositories {

    private static final Logger log = LoggerFactory.getLogger(FormerHostedRepositories.class);

    private static final String SUFFIX = ".git";

    /** One repository the former feature left behind. */
    @Schema(description = "A repository from the removed hosted-marketplace feature that is still in the data store")
    public record FormerHostedRepository(
            @Schema(description = "The marketplace name it was published under")
            String name,

            @Schema(
                    description = "Where it is: an absolute path on the filesystem backend, or the object-store key"
                            + " prefix on the object-store backend")
            String location,

            @Schema(
                    description = "When the commit at the head of its main branch was made; null where it cannot be"
                            + " read cheaply, and always null on the object-store backend")
            Instant lastCommitAt,

            @Schema(description = "The first seven characters of that commit; null as for lastCommitAt")
            String headSha) {}

    private final SkillsGatewayProperties properties;
    private final GitStorage storage;

    public FormerHostedRepositories(SkillsGatewayProperties properties, GitStorage storage) {
        this.properties = properties;
        this.storage = storage;
    }

    /** Every remaining repository, by name; empty when there is nothing, which is the usual case. */
    public List<FormerHostedRepository> list() throws IOException {
        if (storage instanceof ObjectStoreGitStorage objectStore) {
            List<FormerHostedRepository> found = new ArrayList<>();
            for (String name : objectStore.formerHostedMarketplaces()) {
                found.add(new FormerHostedRepository(name, objectStore.formerHostedPrefix(name), null, null));
            }
            return found;
        }
        return listFilesystem(properties.dataDir().resolve("hosted"));
    }

    private static List<FormerHostedRepository> listFilesystem(Path hostedDirectory) throws IOException {
        if (!Files.isDirectory(hostedDirectory)) {
            return List.of();
        }
        List<FormerHostedRepository> found = new ArrayList<>();
        try (Stream<Path> children = Files.list(hostedDirectory)) {
            for (Path child :
                    children.sorted(Comparator.comparing(Path::getFileName)).toList()) {
                String file = child.getFileName().toString();
                if (!file.endsWith(SUFFIX)) {
                    continue;
                }
                String name = file.substring(0, file.length() - SUFFIX.length());
                // Never follow a link out of the folder, and never list a stray file or a odd name.
                if (!MarketplaceName.isValid(name) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                found.add(describe(name, child));
            }
        }
        return found;
    }

    private static FormerHostedRepository describe(String name, Path directory) {
        String location = directory.toAbsolutePath().toString();
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(directory.toFile())
                .setMustExist(true)
                .build()) {
            ObjectId head = repository.resolve(Marketplace.LINEAGE_REF);
            if (head == null) {
                return new FormerHostedRepository(name, location, null, null);
            }
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(head);
                return new FormerHostedRepository(
                        name,
                        location,
                        Instant.ofEpochSecond(commit.getCommitTime()),
                        head.name().substring(0, 7));
            }
        } catch (IOException | RuntimeException e) {
            log.debug("could not read the last commit of the former hosted repository at {}", location, e);
            return new FormerHostedRepository(name, location, null, null);
        }
    }
}

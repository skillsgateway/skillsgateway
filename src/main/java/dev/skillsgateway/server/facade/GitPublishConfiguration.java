package dev.skillsgateway.server.facade;

import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.UploadPack;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The publication endpoint (GW_0102): the one place the gateway accepts a git push.
 *
 * <p>It is deliberately a second servlet rather than a mode on {@link GitFacadeConfiguration},
 * which keeps its {@code setReceivePackFactory(null)} untouched. Nothing here can reach a
 * published repository, and nothing on the consumer facade can reach a write path — the two are
 * separate objects resolving from separate directories, so no flag can confuse one for the other.
 *
 * <p>What it opens is the hosted marketplace's <em>origin</em> repository: the publisher's source
 * of record, which is neither quarantine nor published. Ingestion fetches out of it, so quarantine
 * keeps its property of having exactly one writer.
 *
 * <p>Authorization is the token's push scope, checked before the repository is opened, and every
 * failure — bad name, unscoped token, upstream marketplace, unknown marketplace — answers
 * not-found alike, so a credential cannot map what else the gateway governs (the rule GW_0064 set
 * for fetch scopes).
 */
@Configuration
public class GitPublishConfiguration {

    private static final Pattern MARKETPLACE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");

    private final GitStorage storage;
    private final MarketplaceRepository marketplaceRepository;
    private final FetchAuditHook auditHook;
    private final HostedPushHook pushHook;

    public GitPublishConfiguration(
            GitStorage storage,
            MarketplaceRepository marketplaceRepository,
            FetchAuditHook auditHook,
            HostedPushHook pushHook) {
        this.storage = storage;
        this.marketplaceRepository = marketplaceRepository;
        this.auditHook = auditHook;
        this.pushHook = pushHook;
    }

    @Bean
    @Requirements({"GW_0102"})
    public ServletRegistrationBean<GitServlet> publishServlet() {
        GitServlet servlet = new GitServlet();
        servlet.setRepositoryResolver(this::resolveOrigin);
        servlet.setReceivePackFactory(this::createReceivePack);
        // Upload-pack too, gated by the same push scope: a publisher clones their own source of
        // record onto a new machine or into CI. It reads the repository that credential can
        // already write, and it is not quarantine, so no unapproved snapshot becomes fetchable.
        servlet.setUploadPackFactory(this::createUploadPack);
        return new ServletRegistrationBean<>(servlet, "/publish/*");
    }

    /** Push scope, then hosted-ness, then existence — every failure answers alike. */
    @Requirements({"GW_0102"})
    Repository resolveOrigin(HttpServletRequest request, String name)
            throws RepositoryNotFoundException, ServiceMayNotContinueException {
        String marketplace = name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
        if (!MARKETPLACE_NAME.matcher(marketplace).matches()) {
            throw new RepositoryNotFoundException(name);
        }
        var token = auditHook.currentToken();
        if (token == null || !token.permitsPushTo(marketplace)) {
            throw new RepositoryNotFoundException(name);
        }
        Marketplace registered = marketplaceRepository
                .findByName(marketplace)
                .filter(Marketplace::hosted)
                .orElseThrow(() -> new RepositoryNotFoundException(name));
        Optional<Repository> origin;
        try {
            origin = storage.hostedIfPresent(registered.name());
        } catch (IOException e) {
            throw new ServiceMayNotContinueException(e.getMessage(), e);
        }
        return origin.orElseThrow(() -> new RepositoryNotFoundException(name));
    }

    @Requirements({"GW_0102"})
    ReceivePack createReceivePack(HttpServletRequest request, Repository repository) {
        ReceivePack receivePack = new ReceivePack(repository);
        receivePack.setRefLogIdent(pushHook.identityOf(auditHook.currentPrincipal()));
        receivePack.setPreReceiveHook(pushHook);
        receivePack.setPostReceiveHook(pushHook);
        // A publisher pushes content, never a repository's shape: no ref deletion, no
        // non-fast-forward except where the marketplace's policy allows it (both enforced in the
        // hook, which knows the marketplace), and never an atomic-less partial application.
        receivePack.setAllowDeletes(false);
        receivePack.setAtomic(true);
        return receivePack;
    }

    @Requirements({"GW_0102"})
    UploadPack createUploadPack(HttpServletRequest request, Repository repository) {
        return new UploadPack(repository);
    }
}

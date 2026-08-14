package io.github.jimisola.skillsgateway.facade;

import io.github.jimisola.skillsgateway.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreUploadHook;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.UploadPack;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GitFacadeConfiguration {

    private static final Pattern MARKETPLACE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");
    private static final String SERVED_REF = "refs/heads/main";

    private final GitStorage storage;
    private final FetchAuditHook auditHook;

    public GitFacadeConfiguration(GitStorage storage, FetchAuditHook auditHook) {
        this.storage = storage;
        this.auditHook = auditHook;
    }

    /** Read-only by construction: receive-pack is disabled, so pushes are impossible. */
    @Bean
    @Requirements({"GW_0006"})
    public ServletRegistrationBean<GitServlet> gitServlet() {
        GitServlet servlet = new GitServlet();
        servlet.setRepositoryResolver(this::resolvePublished);
        servlet.setUploadPackFactory(this::createUploadPack);
        servlet.setReceivePackFactory(null);
        return new ServletRegistrationBean<>(servlet, "/git/*");
    }

    /** The facade only ever opens published repositories; quarantine is unreachable from here. */
    @Requirements({"GW_0007"})
    Repository resolvePublished(HttpServletRequest request, String name)
            throws RepositoryNotFoundException, ServiceMayNotContinueException {
        String marketplace = name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
        if (!MARKETPLACE_NAME.matcher(marketplace).matches()) {
            throw new RepositoryNotFoundException(name);
        }
        Optional<Repository> serving;
        try {
            serving = storage.publishedIfServing(marketplace);
        } catch (IOException e) {
            throw new ServiceMayNotContinueException(e.getMessage(), e);
        }
        Repository repository = serving.orElseThrow(() -> new RepositoryNotFoundException(name));
        if (request.getRequestURI().endsWith("/info/refs")) {
            String sha;
            try {
                ObjectId main = repository.resolve(SERVED_REF);
                sha = main == null ? null : main.name();
            } catch (IOException e) {
                throw new ServiceMayNotContinueException(e.getMessage(), e);
            }
            auditHook.record(
                    request.getRemoteAddr(), auditHook.currentPrincipal(), marketplace, "info-refs", SERVED_REF, sha);
        }
        return repository;
    }

    UploadPack createUploadPack(HttpServletRequest request, Repository repository) {
        UploadPack uploadPack = new UploadPack(repository);
        uploadPack.setPreUploadHook(new AuditingPreUploadHook(
                request.getRemoteAddr(), auditHook.currentPrincipal(), marketplaceName(repository)));
        return uploadPack;
    }

    private static String marketplaceName(Repository repository) {
        String directory = repository.getDirectory().getName();
        return directory.endsWith(".git") ? directory.substring(0, directory.length() - 4) : directory;
    }

    private final class AuditingPreUploadHook implements PreUploadHook {

        private final String source;
        private final String principal;
        private final String marketplace;

        AuditingPreUploadHook(String source, String principal, String marketplace) {
            this.source = source;
            this.principal = principal;
            this.marketplace = marketplace;
        }

        @Override
        public void onBeginNegotiateRound(UploadPack up, Collection<? extends ObjectId> wants, int cntOffered) {
            // only pack sends are audited
        }

        @Override
        public void onEndNegotiateRound(
                UploadPack up, Collection<? extends ObjectId> wants, int cntCommon, int cntNotFound, boolean ready) {
            // only pack sends are audited
        }

        @Override
        public void onSendPack(
                UploadPack up, Collection<? extends ObjectId> wants, Collection<? extends ObjectId> haves) {
            for (ObjectId want : wants) {
                auditHook.record(source, principal, marketplace, "upload-pack", SERVED_REF, want.name());
            }
        }
    }
}

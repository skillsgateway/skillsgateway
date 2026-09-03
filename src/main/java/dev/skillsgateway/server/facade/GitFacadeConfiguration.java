package dev.skillsgateway.server.facade;

import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreUploadHook;
import org.eclipse.jgit.transport.RefFilter;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.UploadPack;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GitFacadeConfiguration {

    private static final Pattern MARKETPLACE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");
    private static final String SERVED_REF = "refs/heads/main";
    private static final String SNAPSHOT_REF_PREFIX = "refs/snapshots/";

    /**
     * What the facade puts on the wire, stated rather than inherited (GW_0134).
     *
     * <p>{@code UploadPack} advertises every reference the repository holds unless told otherwise,
     * and under the default {@code RequestPolicy.ADVERTISED} every advertised tip is a legal
     * {@code want}. So the served surface was whatever happened to be in the repository: the
     * catalog's {@code refs/catalog/*} scaffolding, and any staging reference publication needs
     * before it commits to serving something. An allowlist makes the surface a decision.
     *
     * <p>Two namespaces belong on it. {@code refs/heads/main} is the served tip. Snapshot
     * references are advertised deliberately — an approved snapshot stays fetchable by name even
     * once a later approval supersedes it, which is why {@code GitStorage.unpublish} removes both
     * when a snapshot is revoked. Nothing else is served, and a reference the gateway uses to build
     * what it serves is not the same thing as a reference it serves.
     *
     * <p>{@code HEAD} stays: it is in the map the filter receives, and a clone reads it to learn
     * which branch to check out. Dropping it leaves a client that can fetch but cannot clone.
     */
    private static final RefFilter SERVED_REFS = refs -> {
        Map<String, Ref> served = new LinkedHashMap<>();
        refs.forEach((name, ref) -> {
            if (Constants.HEAD.equals(name) || SERVED_REF.equals(name) || name.startsWith(SNAPSHOT_REF_PREFIX)) {
                served.put(name, ref);
            }
        });
        return served;
    };

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
    @Requirements({"GW_0007", "GW_0064"})
    Repository resolvePublished(HttpServletRequest request, String name)
            throws RepositoryNotFoundException, ServiceMayNotContinueException {
        String marketplace = name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
        if (!MARKETPLACE_NAME.matcher(marketplace).matches()) {
            throw new RepositoryNotFoundException(name);
        }
        // Scope enforcement (GW_0064), before the storage lookup: an out-of-scope request gets
        // the same not-found a nonexistent marketplace gets, so a scoped token cannot probe what
        // else the gateway governs. An unscoped token permits everything.
        var token = auditHook.currentToken();
        if (token != null && !token.permitsMarketplace(marketplace)) {
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

    @Requirements({"GW_0134"})
    UploadPack createUploadPack(HttpServletRequest request, Repository repository) {
        UploadPack uploadPack = new UploadPack(repository);
        uploadPack.setRefFilter(SERVED_REFS);
        uploadPack.setPreUploadHook(new AuditingPreUploadHook(
                request.getRemoteAddr(), auditHook.currentPrincipal(), marketplaceName(repository)));
        return uploadPack;
    }

    private static String marketplaceName(Repository repository) {
        String directory = repository.getDirectory().getName();
        return directory.endsWith(".git") ? directory.substring(0, directory.length() - 4) : directory;
    }

    /**
     * Which advertised ref a transferred {@code want} resolves to (GW_0154), or {@code null} when
     * none does.
     *
     * <p>{@code PreUploadHook} is handed object ids, not ref names, and JGit exposes no
     * {@code want-ref} line to it, so this mapping is the only derivation available — and it is
     * taken over the set {@link #SERVED_REFS} already filtered, so the ledger cannot name a
     * reference the facade did not advertise.
     *
     * <p><b>The tip wins the ambiguity.</b> While a snapshot is current, {@code refs/heads/main}
     * and its {@code refs/snapshots/<sha>} are the same commit, so a clone and a fetch by name send
     * an identical want list; no rule can separate them. Recording the tip keeps every entry that
     * is already correct correct, and confines the new value to the case that is wrong today — a
     * want that is <em>not</em> the tip, which can only have come from a snapshot reference the
     * marketplace no longer serves through main. {@code HEAD} is advertised and points at the tip
     * too, so this branch subsumes it and the ledger names the branch rather than the symref.
     *
     * <p><b>No match records nothing.</b> Under the default {@code RequestPolicy.ADVERTISED} every
     * want is an advertised tip, so this is unreachable through the servlet — but falling back to a
     * constant is the defect this replaces. A column whose purpose is to say what was asked for has
     * to be able to say it does not know; {@code sha} still pins the delivered content exactly.
     */
    @Requirements({"GW_0154"})
    static String wantedRef(Map<String, Ref> advertised, ObjectId want) {
        Ref tip = advertised.get(SERVED_REF);
        if (tip != null && want.equals(tip.getObjectId())) {
            return SERVED_REF;
        }
        // Exactly one snapshot reference can match, since an approval pins refs/snapshots/<sha> at
        // <sha>; min() makes the answer independent of map ordering rather than of that invariant.
        return advertised.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(SNAPSHOT_REF_PREFIX))
                .filter(entry -> want.equals(entry.getValue().getObjectId()))
                .map(Map.Entry::getKey)
                .min(Comparator.naturalOrder())
                .orElse(null);
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
        @Requirements({"GW_0008", "GW_0154"})
        public void onSendPack(
                UploadPack up, Collection<? extends ObjectId> wants, Collection<? extends ObjectId> haves) {
            Map<String, Ref> advertised = up.getAdvertisedRefs();
            for (ObjectId want : wants) {
                String ref = advertised == null ? null : wantedRef(advertised, want);
                auditHook.record(source, principal, marketplace, "upload-pack", ref, want.name());
            }
        }
    }
}

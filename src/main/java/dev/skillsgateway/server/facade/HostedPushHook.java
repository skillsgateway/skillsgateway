package dev.skillsgateway.server.facade;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.ingestion.IngestionService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import io.github.reqstool.annotations.Requirements;
import java.util.Collection;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What a publisher may do to a hosted marketplace's origin repository (GW_0102), and what happens
 * once they have done it (GW_0103).
 *
 * <p>Three rules, all refusing rather than repairing. Only the single lineage ref may be updated —
 * the same one-history guarantee GW_0017 makes for an upstream's default branch, which is what
 * makes a snapshot's provenance a straight line. No ref may be deleted. And history may not be
 * rewritten unless the marketplace was registered saying it may, in which case both tips land on
 * the append-only ledger, so "what was approved has since been rewritten" stays answerable.
 *
 * <p>Ingestion happens after the push completes, not inside it: vetting a snapshot inside the
 * receive transaction would make {@code git push} block on the connector chain and make the
 * chain's timeout the publisher's timeout.
 */
@Component
public class HostedPushHook implements PreReceiveHook, PostReceiveHook {

    private static final Logger log = LoggerFactory.getLogger(HostedPushHook.class);

    static final String EVENT_PUSHED = "marketplace-pushed";
    static final String EVENT_REWRITTEN = "marketplace-lineage-rewritten";

    private final MarketplaceRepository marketplaceRepository;
    private final IngestionService ingestionService;
    private final AdminAuditLogger auditLogger;
    private final FetchAuditHook auditHook;

    public HostedPushHook(
            MarketplaceRepository marketplaceRepository,
            IngestionService ingestionService,
            AdminAuditLogger auditLogger,
            FetchAuditHook auditHook) {
        this.marketplaceRepository = marketplaceRepository;
        this.ingestionService = ingestionService;
        this.auditLogger = auditLogger;
        this.auditHook = auditHook;
    }

    /** Reflog identity for a push, so the origin repository records who moved the lineage. */
    PersonIdent identityOf(String principal) {
        String who = principal == null || principal.isBlank() ? "unknown" : principal;
        return new PersonIdent(who, who + "@skills-gateway.invalid");
    }

    @Override
    @Requirements({"GW_0102"})
    public void onPreReceive(ReceivePack receivePack, Collection<ReceiveCommand> commands) {
        Optional<Marketplace> marketplace = marketplaceOf(receivePack);
        boolean rewritable = marketplace
                .map(m -> Marketplace.PUSH_ALLOW_REWRITE.equals(m.pushPolicy()))
                .orElse(false);

        for (ReceiveCommand command : commands) {
            if (!Marketplace.LINEAGE_REF.equals(command.getRefName())) {
                reject(
                        command,
                        "only %s may be published; a marketplace has one lineage".formatted(Marketplace.LINEAGE_REF));
                continue;
            }
            if (command.getType() == ReceiveCommand.Type.DELETE) {
                reject(command, "the lineage cannot be deleted");
                continue;
            }
            if (command.getType() == ReceiveCommand.Type.UPDATE_NONFASTFORWARD && !rewritable) {
                reject(
                        command,
                        "this marketplace is append-only; rewriting the lineage would change what was reviewed");
            }
        }
    }

    @Override
    @Requirements({"GW_0102", "GW_0103"})
    public void onPostReceive(ReceivePack receivePack, Collection<ReceiveCommand> commands) {
        Optional<Marketplace> found = marketplaceOf(receivePack);
        if (found.isEmpty()) {
            return;
        }
        Marketplace marketplace = found.get();
        String principal = auditHook.currentPrincipal();

        for (ReceiveCommand command : commands) {
            if (command.getResult() != ReceiveCommand.Result.OK
                    || !Marketplace.LINEAGE_REF.equals(command.getRefName())) {
                continue;
            }
            String from = ObjectId.zeroId().equals(command.getOldId())
                    ? "(new)"
                    : command.getOldId().name();
            String to = command.getNewId().name();
            auditLogger.record(principal, marketplace.name(), EVENT_PUSHED, to, "from=%s".formatted(from));
            if (command.getType() == ReceiveCommand.Type.UPDATE_NONFASTFORWARD) {
                // Permitted by policy, but never silent: this is the entry that answers "the
                // lineage under that approved snapshot is not the one it was approved from".
                auditLogger.record(principal, marketplace.name(), EVENT_REWRITTEN, to, "rewrote=%s".formatted(from));
            }
            ingest(marketplace, principal);
        }
    }

    /**
     * Ingestion is best-effort with respect to the push: the objects are safely in the origin
     * repository, which is what git promised the publisher. A failure here leaves the content
     * pushed and un-ingested, recoverable by the ordinary ingest endpoint, and is logged rather
     * than turned into a push failure the publisher cannot act on.
     */
    @Requirements({"GW_0103"})
    private void ingest(Marketplace marketplace, String principal) {
        try {
            ingestionService.ingest(marketplace);
        } catch (RuntimeException e) {
            log.warn("push to '{}' landed but ingestion failed; re-ingest to pick it up", marketplace.name(), e);
            auditLogger.record(principal, marketplace.name(), "marketplace-push-ingest-failed", null, e.getMessage());
        }
    }

    private Optional<Marketplace> marketplaceOf(ReceivePack receivePack) {
        Repository repository = receivePack.getRepository();
        if (repository == null || repository.getDirectory() == null) {
            return Optional.empty();
        }
        String directory = repository.getDirectory().getName();
        String name = directory.endsWith(".git") ? directory.substring(0, directory.length() - 4) : directory;
        return marketplaceRepository.findByName(name).filter(Marketplace::hosted);
    }

    private static void reject(ReceiveCommand command, String reason) {
        command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, reason);
    }
}

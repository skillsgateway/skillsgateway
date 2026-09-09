package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.persistence.ActorType;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * The check {@code ApprovalService.repair}'s javadoc has always claimed exists (GW_APPROVAL_0014).
 *
 * <p>An approval records the decision and then publishes. When publication fails, the decision is
 * put back — and when <em>that</em> fails too, the row says {@code approved} while nothing is
 * served. GW_APPROVAL_0012 — An approval reports success only when the publication happened
 * requires that double failure be reported rather than discarded, and it is: at error, and attached
 * to the exception that propagates. Nothing reconciled it afterwards. The comment naming the
 * control that would have was the only thing standing where the control should be, which is worse
 * than no comment, because it retires the concern in the reader's mind.
 *
 * <p>This runs at startup, after every singleton exists — so Flyway has migrated — and before the
 * web server starts. That ordering is not incidental: repairing the served refs moves
 * {@code refs/heads/main}, and doing it while nothing can be fetched means no client ever observes
 * the intermediate state.
 *
 * <p><b>It repairs one direction and only reports the other.</b> A snapshot the database says is
 * approved but storage is not serving is republished: publication is idempotent by SHA, so the
 * repair is additive and cannot destroy anything. A ref storage is serving for a snapshot the
 * database does not call approved is <em>reported</em> and left alone. That asymmetry is deliberate.
 * Deleting served refs on the strength of a database comparison is the direction that can go
 * catastrophically wrong — a migration mid-flight, a restore from a stale dump — and it would do so
 * silently and at scale. Serving unapproved content is the more serious condition, and precisely
 * because it is, retracting it is a decision for a person with the report in hand.
 */
@Component
public class PublicationReconciler implements SmartInitializingSingleton {

    /** The gateway's own name on the ledger entries this writes. */
    public static final String ACTOR = "publication-reconciler";

    private static final Logger log = LoggerFactory.getLogger(PublicationReconciler.class);

    private final GitStorage storage;
    private final MarketplaceRepository marketplaceRepository;
    private final SnapshotRepository snapshotRepository;
    private final AdminAuditLogger auditLogger;

    public PublicationReconciler(
            GitStorage storage,
            MarketplaceRepository marketplaceRepository,
            SnapshotRepository snapshotRepository,
            AdminAuditLogger auditLogger) {
        this.storage = storage;
        this.marketplaceRepository = marketplaceRepository;
        this.snapshotRepository = snapshotRepository;
        this.auditLogger = auditLogger;
    }

    /** What one pass found and what it did about it. */
    public record Reconciliation(int marketplaces, List<String> repaired, List<String> servedNotApproved) {}

    @Override
    @Requirements({"GW_APPROVAL_0014"})
    public void afterSingletonsInstantiated() {
        reconcile("startup");
    }

    /**
     * Compares what the database says is approved against what storage is serving, per marketplace.
     *
     * <p>A marketplace serving nothing is repaired, not skipped — that is precisely the state a
     * double failure on a first publication leaves. A marketplace whose storage could not be
     * <em>read</em> is skipped, because treating "I could not look" as "there is nothing there" is
     * the failure mode this method must not have.
     */
    @Requirements({"GW_APPROVAL_0014"})
    public Reconciliation reconcile(String trigger) {
        List<String> repaired = new ArrayList<>();
        List<String> servedNotApproved = new ArrayList<>();
        List<Marketplace> marketplaces = marketplaceRepository.list();

        for (Marketplace marketplace : marketplaces) {
            List<Snapshot> approved = snapshotRepository.approvedByMarketplace(marketplace.id());
            Optional<Set<String>> served = servedShas(marketplace.name());
            if (served.isEmpty()) {
                // Unreadable, which is not the same as serving nothing. Skipped entirely.
                continue;
            }
            Set<String> servedNow = served.get();

            boolean anyRepaired = false;
            for (Snapshot snapshot : approved) {
                if (servedNow.contains(snapshot.sha())) {
                    continue;
                }
                if (republish(marketplace.name(), snapshot)) {
                    repaired.add(marketplace.name() + "/" + snapshot.sha());
                    anyRepaired = true;
                }
            }
            // Republishing an older snapshot moves the served tip onto it, so the newest approved
            // one is written last to put the tip back. Idempotent, and cheap enough to do
            // unconditionally after any repair rather than reason about which case needs it.
            if (anyRepaired && !approved.isEmpty()) {
                republish(marketplace.name(), approved.getLast());
            }

            Set<String> approvedShas =
                    approved.stream().map(Snapshot::sha).collect(java.util.stream.Collectors.toSet());
            for (String sha : servedNow) {
                if (!approvedShas.contains(sha)) {
                    report(marketplace.name(), "publication-served-not-approved", sha);
                    servedNotApproved.add(marketplace.name() + "/" + sha);
                }
            }
        }

        Reconciliation result =
                new Reconciliation(marketplaces.size(), List.copyOf(repaired), List.copyOf(servedNotApproved));
        if (!repaired.isEmpty() || !servedNotApproved.isEmpty()) {
            log.warn(
                    "publication reconciliation ({}): {} marketplaces, {} republished, {} served without an approval",
                    trigger,
                    result.marketplaces(),
                    result.repaired().size(),
                    result.servedNotApproved().size());
        }
        return result;
    }

    /**
     * The SHAs this marketplace is serving, or empty when that could not be determined.
     *
     * <p>The distinction is the one thing this method exists to make. A marketplace serving nothing
     * answers an empty <em>set</em>: that is a known state, and it is exactly what a double failure
     * on a marketplace's first publication leaves behind, so it must reach the repair. A marketplace
     * whose storage could not be read answers an empty <em>Optional</em> and is skipped, because "I
     * could not look" must never be read as "there is nothing there".
     */
    private Optional<Set<String>> servedShas(String marketplace) {
        try {
            Optional<Repository> serving = storage.publishedIfServing(marketplace);
            if (serving.isEmpty()) {
                return Optional.of(Set.of());
            }
            try (Repository published = serving.get()) {
                Set<String> shas = new LinkedHashSet<>();
                for (Ref ref : published.getRefDatabase().getRefsByPrefix(GitStorage.SNAPSHOT_REF_PREFIX)) {
                    shas.add(ref.getName().substring(GitStorage.SNAPSHOT_REF_PREFIX.length()));
                }
                return Optional.of(shas);
            }
        } catch (IOException | RuntimeException e) {
            // One unreadable marketplace must not stop the pass, and must not be mistaken for one
            // that is serving nothing — that reading is what would trigger a spurious republication.
            log.error("publication reconciliation could not read what '{}' is serving", marketplace, e);
            return Optional.empty();
        }
    }

    private boolean republish(String marketplace, Snapshot snapshot) {
        try {
            storage.publish(marketplace, snapshot.sha());
            report(marketplace, "publication-repaired", snapshot.sha());
            return true;
        } catch (IOException | RuntimeException e) {
            log.error(
                    "publication reconciliation could not republish snapshot {} of '{}': it is recorded as approved"
                            + " and is still not served",
                    snapshot.id(),
                    marketplace,
                    e);
            return false;
        }
    }

    private void report(String marketplace, String event, String detail) {
        auditLogger.recordAs(ActorType.SYSTEM, ACTOR, marketplace, event, detail);
    }
}

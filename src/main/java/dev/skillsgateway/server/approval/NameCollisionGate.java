package dev.skillsgateway.server.approval;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.policy.SnapshotFactsRepository;
import dev.skillsgateway.server.policy.SnapshotFactsService;
import dev.skillsgateway.server.vetting.Finding;
import dev.skillsgateway.server.vetting.Severity;
import dev.skillsgateway.server.vetting.Waiver;
import dev.skillsgateway.server.vetting.WaiverEvaluation;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The plugin-name collision precondition (GW_APPROVAL_0019): a question about the approved estate,
 * asked at the approval request and never in a chain run (ADR 0015).
 *
 * <p>Only the snapshot seeking admission is judged. An approved snapshot is never re-evaluated by
 * this class and nothing here can withdraw one: in a typosquat the incumbent is the victim.
 */
@Service
public class NameCollisionGate {

    /** The rule a waiver names to accept a collision (GW_APPROVAL_0020). */
    public static final String RULE_ID = "plugin-name-collision";

    /** What stands in a suppression's vetter field: this gate, which is not a vetter. */
    public static final String SOURCE = "name-collision-gate";

    /**
     * The one advisory lock every approval's transition takes (GW_APPROVAL_0019.3). Any constant would
     * do; this one is the ASCII of "sgw-name" so it is recognisable in {@code pg_locks}.
     */
    static final long TRANSITION_LOCK = 0x7367772d6e616d65L;

    private final SnapshotFactsService factsService;
    private final SnapshotFactsRepository factsRepository;
    private final WaiverService waiverService;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final boolean enabled;

    public NameCollisionGate(
            SnapshotFactsService factsService,
            SnapshotFactsRepository factsRepository,
            WaiverService waiverService,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            SkillsGatewayProperties properties) {
        this.factsService = factsService;
        this.factsRepository = factsRepository;
        this.waiverService = waiverService;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.enabled = properties.approval().nameCollision().enabled();
    }

    @Schema(
            name = "NameCollisionIncumbent",
            description = "An approved snapshot of another marketplace already carrying a colliding plugin name")
    public record Incumbent(
            @Schema(description = "The incumbent's marketplace")
            String marketplace,

            @Schema(description = "The approved snapshot carrying the name")
            long snapshotId,

            @Schema(description = "The plugin name as the incumbent declares it")
            String pluginName) {}

    @Schema(
            name = "NameCollision",
            description = "A plugin name this snapshot introduces that collides with the approved estate")
    public record Collision(
            @Schema(description = "The plugin name as this snapshot declares it")
            String pluginName,

            @Schema(description = "The manifest entry that declares it, as path:line")
            String location,

            @Schema(description = "Every approved snapshot of another marketplace carrying a colliding name")
            List<Incumbent> incumbents,

            @Schema(description = "The finding a waiver accepts; its id is the rule a waiver names")
            Finding finding,

            @Schema(description = "The active waiver covering this collision, or null when none does")
            WaiverEvaluation.Suppression waiver) {

        @JsonProperty("covered")
        @Schema(description = "Whether an active waiver covers this collision")
        public boolean covered() {
            return waiver != null;
        }
    }

    @Schema(
            name = "NameCollisionCheck",
            description = "What the name-collision rule would say about approving this snapshot now")
    public record Check(
            @Schema(
                    description = "Whether the rule is switched on (" + SkillsGatewayProperties.NameCollision.CONFIG_KEY
                            + ")")
            boolean enabled,

            @Schema(description = "False when the snapshot's plugin names could not be read; approval is then refused")
            boolean inventoryAvailable,

            @Schema(description = "Every plugin name this snapshot introduces that collides, covered or not")
            List<Collision> collisions) {

        public Check {
            collisions = List.copyOf(collisions);
        }

        /** Whether an approval now would be refused by this rule. */
        @JsonProperty("refused")
        @Schema(description = "Whether an approval requested now would be refused by this rule")
        public boolean refused() {
            return enabled && (!inventoryAvailable || !uncovered().isEmpty());
        }

        public List<Collision> uncovered() {
            return collisions.stream().filter(c -> !c.covered()).toList();
        }

        /** The waivers this rule relies on, to be ledgered and shown to the four-eyes rule (GW_APPROVAL_0020). */
        public List<WaiverEvaluation.Suppression> suppressions() {
            return collisions.stream()
                    .filter(Collision::covered)
                    .map(Collision::waiver)
                    .toList();
        }
    }

    /**
     * What the rule says about approving this snapshot now, without deciding anything. Makes sure the
     * snapshot's own plugin names are indexed first — built from the pinned manifest when ingestion did
     * not record them — so an approved snapshot is always one the estate query can see.
     */
    @Requirements({"GW_APPROVAL_0019", "GW_APPROVAL_0019.2", "GW_APPROVAL_0020", "GW_APPROVAL_0021"})
    public Check evaluate(Snapshot snapshot, Marketplace marketplace) {
        boolean indexed = factsService.record(snapshot, marketplace);
        if (!enabled) {
            return new Check(false, indexed, List.of());
        }
        return indexed ? compare(snapshot) : new Check(true, false, List.of());
    }

    /** {@link #evaluate}, raising the refusal when there is one. */
    @Requirements({"GW_APPROVAL_0019", "GW_APPROVAL_0020"})
    public Check require(Snapshot snapshot, Marketplace marketplace) {
        return refuseIfRefused(snapshot, evaluate(snapshot, marketplace));
    }

    /**
     * Runs {@code transition} inside one transaction holding the approvals' advisory lock, after a
     * final evaluation inside that same transaction (GW_APPROVAL_0019.3). The lock is released at commit,
     * so the next approval's evaluation sees this one's row; two colliding approvals therefore cannot
     * both pass, and the second is refused as a collision with the first. A refusal raised here rolls
     * the transaction back before the transition has happened.
     */
    @Requirements({"GW_APPROVAL_0019.3"})
    public <T> T guarded(Snapshot snapshot, Supplier<T> transition) {
        if (!enabled) {
            return transition.get();
        }
        return transactions.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                    .param("key", TRANSITION_LOCK)
                    .query((rs, row) -> Boolean.TRUE)
                    .single();
            boolean indexed = factsRepository.indexed(snapshot.id());
            refuseIfRefused(snapshot, indexed ? compare(snapshot) : new Check(true, false, List.of()));
            return transition.get();
        });
    }

    private static Check refuseIfRefused(Snapshot snapshot, Check check) {
        if (check.enabled() && !check.inventoryAvailable()) {
            throw new PluginInventoryUnavailableException(snapshot.id());
        }
        if (check.refused()) {
            throw new NameCollisionException(snapshot.id(), check.uncovered());
        }
        return check;
    }

    /** The comparison itself, over the recorded index (GW_APPROVAL_0019.2). */
    private Check compare(Snapshot snapshot) {
        List<SnapshotFactsRepository.EstateName> estate = factsRepository.approvedEstate(snapshot.id());
        Set<String> history = new HashSet<>();
        List<Keyed> elsewhere = new ArrayList<>();
        for (SnapshotFactsRepository.EstateName name : estate) {
            Set<String> keys = NameNormalizer.keys(name.name());
            if (name.marketplaceId() == snapshot.marketplaceId()) {
                history.addAll(keys);
            } else {
                elsewhere.add(new Keyed(name, keys));
            }
        }
        List<Waiver> waivers = waiverService.forSnapshot(snapshot);
        Instant now = Instant.now();
        List<Collision> collisions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SnapshotFactsRepository.PluginName plugin : factsRepository.pluginNames(snapshot.id())) {
            if (!seen.add(plugin.name())) {
                continue;
            }
            Set<String> keys = NameNormalizer.keys(plugin.name());
            // Not new to this marketplace: raised and accepted once, when it first arrived.
            if (keys.stream().anyMatch(history::contains)) {
                continue;
            }
            List<Incumbent> incumbents = elsewhere.stream()
                    .filter(other -> other.keys().stream().anyMatch(keys::contains))
                    .map(other -> new Incumbent(
                            other.name().marketplace(),
                            other.name().snapshotId(),
                            other.name().name()))
                    .distinct()
                    .toList();
            if (incumbents.isEmpty()) {
                continue;
            }
            Finding finding =
                    new Finding(RULE_ID, Severity.HIGH, plugin.location(), message(plugin.name(), incumbents));
            collisions.add(new Collision(
                    plugin.name(), plugin.location(), incumbents, finding, covering(waivers, finding, snapshot, now)));
        }
        return new Check(true, true, collisions);
    }

    private static WaiverEvaluation.Suppression covering(
            List<Waiver> waivers, Finding finding, Snapshot snapshot, Instant now) {
        for (Waiver waiver : waivers) {
            if (waiver.covers(finding, snapshot.sha(), now)) {
                return new WaiverEvaluation.Suppression(
                        SOURCE, finding.id(), finding.location(), waiver.id(), waiver.approvedBy(), waiver.expiresAt());
            }
        }
        return null;
    }

    private static String message(String pluginName, List<Incumbent> incumbents) {
        Map<String, List<Incumbent>> byMarketplace =
                incumbents.stream().collect(Collectors.groupingBy(Incumbent::marketplace));
        return "plugin name '%s' collides with %s already approved in another marketplace"
                .formatted(
                        pluginName,
                        byMarketplace.entrySet().stream()
                                .map(entry -> "'%s' in %s"
                                        .formatted(entry.getValue().getFirst().pluginName(), entry.getKey()))
                                .sorted()
                                .collect(Collectors.joining(", ")));
    }

    private record Keyed(SnapshotFactsRepository.EstateName name, Set<String> keys) {}
}

package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.admin.MarketplaceRegistrationService;
import dev.skillsgateway.server.admin.MarketplaceRemovalService;
import dev.skillsgateway.server.admin.MissingRemovalReasonException;
import dev.skillsgateway.server.approval.RevocationService;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRemovedException;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import dev.skillsgateway.server.retention.RetentionService;
import dev.skillsgateway.server.roles.RoleGrant;
import dev.skillsgateway.server.roles.RoleGrantRepository;
import dev.skillsgateway.server.roles.RoleService;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.webhook.WebhookEvent;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Removing a marketplace and reusing its name (GW_INGEST_0034, GW_INGEST_0035, GW_AUDIT_0009,
 * GW_WEBHOOK_0010).
 *
 * <p>A trust boundary twice over: removal writes to the served set, and a reused name must not inherit
 * content or authority. So most of what is here attacks rather than exercises: refusals that must
 * change nothing, a removed name the facade must refuse even with references left behind, a decision
 * that must be refused under the lock and not only by the early check, and a successor that must
 * start from nothing.
 */
class MarketplaceRemovalTests extends AbstractGatewayTest {

    private static final String REASON = "upstream abandoned";

    @Autowired
    private MarketplaceRemovalService removalService;

    @Autowired
    private MarketplaceRegistrationService registrationService;

    @Autowired
    private SnapshotRepository snapshots;

    @Autowired
    private GitStorage storage;

    @Autowired
    private RoleService roleService;

    @Autowired
    private RoleGrantRepository grantRepository;

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private WebhookSubscriberRepository subscriberRepository;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private JdbcClient jdbc;

    // --- GW_INGEST_0034 ---------------------------------------------------------------------

    @Test
    @SVCs({"SVC_GW_INGEST_0034"})
    void removal_withdraws_what_is_served_and_keeps_the_record() throws Exception {
        String name = uniqueName("rmserved");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(name, upstream);
        Snapshot approved = approve(registered.snapshot().id());
        addUpstreamCommit(upstream, "second");
        Snapshot held = ingestionService.ingest(registered.marketplace(), "alice");
        assertThat(gitClone(facadeUrl(name, newPat()), newWorkDir("rmclone-before"))
                        .exitCode())
                .as("the marketplace is served before its removal")
                .isZero();

        MarketplaceRemovalService.Removal removal = removalService.remove(name, "  " + REASON + "  ", "root");

        assertThat(removal.withdrawnSnapshotIds()).containsExactly(approved.id());
        Snapshot withdrawn = snapshots.findById(approved.id()).orElseThrow();
        assertThat(withdrawn.state()).isEqualTo(Snapshot.REVOKED);
        assertThat(withdrawn.revokedKind()).isEqualTo(Snapshot.REVOKED_ADMINISTRATIVELY);
        assertThat(withdrawn.violation()).isEqualTo(MarketplaceRemovalService.WITHDRAWAL_REASON_PREFIX + REASON);
        assertThat(storage.publishedIfServing(name)).as("nothing is published").isEmpty();
        assertThat(gitClone(facadeUrl(name, newPat()), newWorkDir("rmclone-after"))
                        .exitCode())
                .as("a clone of the removed marketplace fails")
                .isNotZero();
        assertThat(snapshots.heldContent(List.of(new SnapshotRepository.MarketplaceSha(name, approved.sha()))))
                .singleElement()
                .satisfies(answer -> assertThat(answer.revoked()).isTrue());

        // Gone from every name-addressed read ...
        assertThat(marketplaceRepository.findByName(name)).isEmpty();
        assertThat(marketplaceRepository.list()).noneMatch(m -> m.name().equals(name));
        // ... and the record survives.
        assertThat(marketplaceRepository.findById(registered.marketplace().id()))
                .isPresent();
        assertThat(snapshots.findById(held.id())).isPresent();
        assertThat(approvalService.provenance(approved.id())).hasValueSatisfying(p -> assertThat(p.upstreamUrl())
                .isEqualTo(registered.marketplace().url()));
        assertThat(ledger(name))
                .extracting(FetchLogRepository.AuditEntry::event)
                .contains(
                        "marketplace-removed",
                        RevocationService.EVENT_REVOKED,
                        RevocationService.EVENT_UNPUBLISHED,
                        "info-refs");
        assertThat(ledger(name))
                .filteredOn(e -> e.event().equals("marketplace-removed"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.principal()).isEqualTo("root");
                    assertThat(e.detail()).contains(REASON);
                });

        // A held snapshot of a removed marketplace cannot be decided.
        assertThatThrownBy(() -> approve(held.id())).isInstanceOf(MarketplaceRemovedException.class);
        assertThatThrownBy(() -> approvalService.reject(held.id(), "alice"))
                .isInstanceOf(MarketplaceRemovedException.class);
        assertThat(snapshots.findById(held.id()).orElseThrow().state()).isEqualTo(Snapshot.HELD);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0034"})
    void a_refused_removal_changes_nothing() throws Exception {
        String name = uniqueName("rmrefuse");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());

        for (String reason : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> removalService.remove(name, reason, "root"))
                    .isInstanceOf(MissingRemovalReasonException.class);
        }
        assertThatThrownBy(() -> removalService.remove(uniqueName("nosuch"), REASON, "root"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));

        // Over HTTP: no body, a blank reason, and an unknown name.
        mockMvc.perform(delete("/api/v1/marketplaces/{name}", name).with(oidcLogin()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(removeRequest(name, "  ")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(removeRequest(uniqueName("nosuch"), REASON)).andExpect(status().isNotFound());

        assertThat(marketplaceRepository.findByName(name))
                .as("still registered")
                .isPresent();
        assertThat(snapshots.findById(registered.snapshot().id()).orElseThrow().state())
                .isEqualTo(Snapshot.APPROVED);
        assertThat(storage.publishedIfServing(name)).as("still served").isPresent();
        assertThat(ledger(name)).noneMatch(e -> e.event().equals("marketplace-removed"));

        // Removed once; the second removal finds nothing.
        mockMvc.perform(removeRequest(name, REASON)).andExpect(status().isOk());
        mockMvc.perform(removeRequest(name, REASON)).andExpect(status().isNotFound());
        assertThat(ledger(name))
                .filteredOn(e -> e.event().equals("marketplace-removed"))
                .hasSize(1);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0034"})
    void a_removed_marketplace_is_out_of_every_name_addressed_path() throws Exception {
        String name = uniqueName("rmpaths");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        mockMvc.perform(put("/api/v1/marketplaces/{name}/sync", name)
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"scheduled\"}"))
                .andExpect(status().isOk());
        assertThat(marketplaceRepository.dueScheduledSync(Integer.MAX_VALUE))
                .anyMatch(m -> m.id() == registered.marketplace().id());

        removalService.remove(name, REASON, "root");

        assertThat(marketplaceRepository.dueScheduledSync(Integer.MAX_VALUE))
                .as("the sync sweep does not select it")
                .noneMatch(m -> m.id() == registered.marketplace().id());
        mockMvc.perform(post("/api/v1/marketplaces/{name}/ingest", name).with(oidcLogin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/marketplaces/{name}/sync", name)
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"on-demand\"}"))
                .andExpect(status().isNotFound());
        String listing = mockMvc.perform(get("/api/v1/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listing).doesNotContain("\"" + name + "\"");
        mockMvc.perform(post(
                                "/api/v1/snapshots/{id}/approve",
                                registered.snapshot().id())
                        .with(oidcLogin()))
                .andExpect(status().isConflict());
    }

    /**
     * The facade's own check, not the withdrawal: the row is stamped without revoking anything, which
     * is the state a failed unpublish or an approval that published after the withdrawal leaves.
     */
    @Test
    @SVCs({"SVC_GW_INGEST_0034"})
    void the_facade_refuses_a_removed_name_even_with_references_left_behind() throws Exception {
        String name = uniqueName("rmleft");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());
        String pat = newPat();
        assertThat(gitClone(facadeUrl(name, pat), newWorkDir("rmleft-before")).exitCode())
                .isZero();

        marketplaceRepository.retire(registered.marketplace().id(), "root", REASON);

        assertThat(storage.publishedIfServing(name))
                .as("the references are still there")
                .isPresent();
        assertThat(gitClone(facadeUrl(name, pat), newWorkDir("rmleft-after")).exitCode())
                .as("and are not served")
                .isNotZero();
    }

    /** The lock in the decision itself, reached without the early check in front of it. */
    @Test
    @SVCs({"SVC_GW_INGEST_0034"})
    void a_decision_on_a_removed_marketplace_is_refused_by_the_transition_itself() throws Exception {
        Registered registered = registerAndIngest(uniqueName("rmdecide"), createUpstream(DEFAULT_MANIFEST));
        marketplaceRepository.retire(registered.marketplace().id(), "root", REASON);

        assertThatThrownBy(() -> snapshots.decide(registered.snapshot().id(), Snapshot.APPROVED, "alice"))
                .isInstanceOf(MarketplaceRemovedException.class);
        assertThat(snapshots.findById(registered.snapshot().id()).orElseThrow().state())
                .isEqualTo(Snapshot.HELD);
    }

    // --- GW_INGEST_0035 ---------------------------------------------------------------------

    @Test
    @SVCs({"SVC_GW_INGEST_0035"})
    void a_reused_name_is_a_new_marketplace_that_inherits_nothing() throws Exception {
        String name = uniqueName("rmreuse");
        String approver = uniqueName("erin");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered predecessor = registerAndIngest(name, upstream);
        Snapshot served = approve(predecessor.snapshot().id());
        roleService.grant(approver, RoleGrant.APPROVER, name, "root");
        assertThat(approverOf(approver)).containsExactly(name);

        removalService.remove(name, REASON, "root");
        Marketplace successor = registrationService
                .register(name, upstream.toUri().toString(), "root")
                .marketplace();

        assertThat(successor.id()).isNotEqualTo(predecessor.marketplace().id());
        assertThat(storage.publishedIfServing(name))
                .as("the successor serves nothing")
                .isEmpty();
        assertThat(approverOf(approver))
                .as("the predecessor's approver has no grant on it")
                .isEmpty();
        assertThatThrownBy(() ->
                        registrationService.register(name, upstream.toUri().toString(), "root"))
                .as("one live marketplace per name")
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));

        // The same commit comes back held, and the predecessor's withdrawal does not refuse it.
        Snapshot again = ingestionService.ingest(successor, "alice");
        assertThat(again.id()).isNotEqualTo(served.id());
        assertThat(again.sha()).isEqualTo(served.sha());
        assertThat(again.state()).isEqualTo(Snapshot.HELD);
        assertThat(heldAnswer(name, served.sha()).revoked())
                .as("until the successor approves it, a holder is told it was withdrawn")
                .isTrue();
        approve(again.id());
        assertThat(heldAnswer(name, served.sha()).approved()).isTrue();
        assertThat(gitClone(facadeUrl(name, newPat()), newWorkDir("rmreuse-clone"))
                        .exitCode())
                .isZero();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0035"})
    void references_a_removed_marketplace_left_behind_are_not_served_under_its_successor() throws Exception {
        String name = uniqueName("rmleftreuse");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered predecessor = registerAndIngest(name, upstream);
        approve(predecessor.snapshot().id());
        // Stamped without withdrawing: what a failed unpublish leaves.
        marketplaceRepository.retire(predecessor.marketplace().id(), "root", REASON);
        assertThat(storage.publishedIfServing(name)).isPresent();

        registrationService.register(name, upstream.toUri().toString(), "root");

        assertThat(storage.publishedIfServing(name)).isEmpty();
        assertThat(gitClone(facadeUrl(name, newPat()), newWorkDir("rmleftreuse-clone"))
                        .exitCode())
                .isNotZero();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0035"})
    void a_hosted_successor_carries_none_of_its_predecessors_pushes() throws Exception {
        String name = uniqueName("rmhosted");
        registrationService.register(name, null, Marketplace.ORIGIN_HOSTED, null, "root");
        Path pushed = createUpstream(DEFAULT_MANIFEST);
        try (Repository origin = storage.hosted(name);
                Git git = Git.wrap(origin)) {
            git.fetch()
                    .setRemote(pushed.toUri().toString())
                    .setRefSpecs(new RefSpec("+refs/heads/main:" + Marketplace.LINEAGE_REF))
                    .call();
            assertThat(origin.exactRef(Marketplace.LINEAGE_REF)).isNotNull();
        }

        removalService.remove(name, REASON, "root");
        registrationService.register(name, null, Marketplace.ORIGIN_HOSTED, null, "root");

        try (Repository origin = storage.hosted(name)) {
            assertThat(origin.exactRef(Marketplace.LINEAGE_REF)).isNull();
        }
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0035"})
    void purging_a_predecessor_snapshot_keeps_the_successors_copy_of_the_same_commit() throws Exception {
        String name = uniqueName("rmpurge");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered predecessor = registerAndIngest(name, upstream);
        removalService.remove(name, REASON, "root");
        Marketplace successor = registrationService
                .register(name, upstream.toUri().toString(), "root")
                .marketplace();
        Snapshot again = ingestionService.ingest(successor, "alice");
        assertThat(again.sha()).isEqualTo(predecessor.snapshot().sha());

        retentionService.softDelete(predecessor.snapshot().id(), "test", "root");
        jdbc.sql("UPDATE snapshots SET purge_after = :past WHERE id = :id")
                .param("past", java.time.OffsetDateTime.now().minusMinutes(1))
                .param("id", predecessor.snapshot().id())
                .update();
        retentionService.compact("root");

        assertThat(snapshots.findById(predecessor.snapshot().id()))
                .as("the predecessor's row is purged")
                .isEmpty();
        try (Repository quarantine = storage.quarantine(name)) {
            assertThat(quarantine.exactRef("refs/snapshots/" + again.sha()))
                    .as("the successor's pin survives")
                    .isNotNull();
        }
        approve(again.id());
        assertThat(storage.publishedIfServing(name)).isPresent();
    }

    // --- GW_AUDIT_0009 ----------------------------------------------------------------------

    @Test
    @SVCs({"SVC_GW_AUDIT_0009"})
    void ledger_entries_of_two_marketplaces_that_held_one_name_carry_different_ids() throws Exception {
        String name = uniqueName("rmledger");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Marketplace first = registrationService
                .register(name, upstream.toUri().toString(), "root")
                .marketplace();
        ingestionService.ingest(first, "alice");
        removalService.remove(name, REASON, "root");
        Marketplace second = registrationService
                .register(name, upstream.toUri().toString(), "root")
                .marketplace();

        List<FetchLogRepository.AuditEntry> browsed = ledger(name);
        Map<String, Set<Long>> idsByEvent = browsed.stream()
                .collect(Collectors.groupingBy(
                        FetchLogRepository.AuditEntry::event,
                        Collectors.mapping(FetchLogRepository.AuditEntry::marketplaceId, Collectors.toSet())));
        assertThat(idsByEvent.get("marketplace-registered")).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(idsByEvent.get("marketplace-removed")).containsExactly(first.id());
        assertThat(browsed).allSatisfy(e -> assertThat(e.marketplaceId()).isNotNull());

        // The export carries the same column.
        List<FetchLogRepository.AuditEntry> exported =
                fetchLogRepository.entriesAfter(0, Instant.now().plusSeconds(60), Integer.MAX_VALUE).stream()
                        .filter(e -> name.equals(e.marketplace()))
                        .toList();
        assertThat(exported)
                .extracting(FetchLogRepository.AuditEntry::marketplaceId)
                .containsOnly(first.id(), second.id());

        // An entry about no marketplace names none.
        assertThat(fetchLogRepository.entriesBefore(0, 500).stream()
                        .filter(e -> "-".equals(e.marketplace()))
                        .map(FetchLogRepository.AuditEntry::marketplaceId))
                .allMatch(Objects::isNull);
    }

    // --- GW_WEBHOOK_0010 --------------------------------------------------------------------

    @Test
    @SVCs({"SVC_GW_WEBHOOK_0010"})
    void a_removal_is_announced_and_a_refused_one_is_not() throws Exception {
        long subscriber = subscriberRepository
                .create(
                        uniqueName("rmhook"),
                        "http://127.0.0.1:9/",
                        "secret",
                        List.of(WebhookEvent.MARKETPLACE_REMOVED))
                .id();
        String refused = uniqueName("rmhookrefused");
        registerAndIngest(refused, createUpstream(DEFAULT_MANIFEST));
        assertThatThrownBy(() -> removalService.remove(refused, " ", "root"))
                .isInstanceOf(MissingRemovalReasonException.class);
        assertThat(deliveryRepository.listBySubscriber(subscriber)).isEmpty();

        String name = uniqueName("rmhooked");
        registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        removalService.remove(name, "a secret incident", "root");

        List<WebhookDelivery> delivered = deliveryRepository.listBySubscriber(subscriber);
        assertThat(delivered).singleElement().satisfies(d -> assertThat(d.event())
                .isEqualTo(WebhookEvent.MARKETPLACE_REMOVED));
        @SuppressWarnings("unchecked")
        Map<String, Object> body =
                new ObjectMapper().readValue(delivered.getFirst().payload(), Map.class);
        assertThat(body).containsOnlyKeys("event", "occurredAt", "marketplace", "actor", "detail");
        assertThat(body.get("marketplace")).isEqualTo(name);
        assertThat(body.get("actor")).isEqualTo("root");
        assertThat(String.valueOf(body.get("detail"))).doesNotContain("secret incident");
    }

    // --- helpers ----------------------------------------------------------------------------

    private org.springframework.test.web.servlet.RequestBuilder removeRequest(String name, String reason) {
        return delete("/api/v1/marketplaces/{name}", name)
                .with(oidcLogin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"%s\"}".formatted(reason));
    }

    private List<FetchLogRepository.AuditEntry> ledger(String marketplace) {
        return fetchLogRepository.entriesBefore(0, Integer.MAX_VALUE).stream()
                .filter(e -> marketplace.equals(e.marketplace()))
                .toList();
    }

    private List<String> approverOf(String principal) {
        return grantRepository.findByPrincipal(principal).stream()
                .filter(g -> RoleGrant.APPROVER.equals(g.role()))
                .map(RoleGrant::marketplace)
                .toList();
    }

    private SnapshotRepository.HeldContent heldAnswer(String name, String sha) {
        List<SnapshotRepository.HeldContent> answers =
                snapshots.heldContent(List.of(new SnapshotRepository.MarketplaceSha(name, sha)));
        assertThat(answers).hasSize(1);
        return answers.getFirst();
    }
}

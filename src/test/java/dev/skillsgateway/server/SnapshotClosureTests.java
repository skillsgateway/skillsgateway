package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.ingestion.ManifestRewriter;
import dev.skillsgateway.server.ingestion.SnapshotClosure;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotClosureRepository;
import dev.skillsgateway.server.policy.SnapshotFactsService;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The closure as a domain object (GW_0164): recorded with the snapshot, a value copy of what was
 * declared and what it resolved to, gone with the snapshot, and surfaced wherever the snapshot's
 * origin already is.
 */
class SnapshotClosureTests extends AbstractExternalSourceTest {

    @Autowired
    private SnapshotFactsService factsService;

    @Test
    @SVCs({"SVC_GW_0164"})
    void a_resolved_ingestion_records_the_closure_with_the_snapshot() throws Exception {
        Composite composite = ingestComposite("clo");
        Snapshot snapshot = composite.snapshot();

        assertThat(snapshot.upstreamSha()).isEqualTo(composite.upstreamHead());
        assertThat(snapshot.upstreamSha()).isNotEqualTo(snapshot.sha());
        assertThat(commit(composite.marketplace(), snapshot.sha()).getParent(0).name())
                .isEqualTo(snapshot.upstreamSha());

        SnapshotClosureRepository.Closure closure =
                closureRepository.findBySnapshot(snapshot.id()).orElseThrow();
        assertThat(closure.snapshotId()).isEqualTo(snapshot.id());
        assertThat(closure.upstreamSha()).isEqualTo(composite.upstreamHead());
        assertThat(closure.transformerVersion()).isEqualTo(ManifestRewriter.TRANSFORMER_VERSION);
        assertThat(closure.digest())
                .matches("^[0-9a-f]{64}$")
                .isEqualTo(closure.value().digest());
        assertThat(closure.createdAt()).isNotNull();

        assertThat(closure.members()).hasSize(1);
        SnapshotClosure.Member tools = closure.members().getFirst();
        assertThat(tools.pluginName()).isEqualTo("tools");
        assertThat(tools.sourceType()).isEqualTo("github");
        assertThat(tools.declaredSource()).isEqualTo("acme/tools");
        assertThat(tools.declaredRef()).isNull();
        assertThat(tools.declaredSha()).isNull();
        assertThat(tools.cloneUrl()).isEqualTo(composite.cloneUrl());
        assertThat(tools.resolvedSha()).isEqualTo(FORGE.headSha("acme/tools"));
        assertThat(tools.graftPath()).isEqualTo("_plugins/tools");
        // The tree, not the commit: the commit object is unreachable once scaffolding is pruned.
        assertThat(tools.treeSha())
                .isEqualTo(treeAt(composite.marketplace(), snapshot.sha(), "_plugins/tools")
                        .name());
        assertThat(tools.objectCount()).isPositive();
        assertThat(tools.inflatedBytes()).isPositive();
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void a_local_only_snapshot_records_no_closure_and_its_own_commit_as_upstream() throws Exception {
        Registered registered = registerAndIngest(uniqueName("clo"), createUpstream(DEFAULT_MANIFEST));
        Snapshot snapshot = registered.snapshot();

        assertThat(snapshot.state()).isEqualTo(Snapshot.HELD);
        assertThat(snapshot.upstreamSha()).isEqualTo(snapshot.sha());
        assertThat(closureRepository.findBySnapshot(snapshot.id())).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void a_rejected_resolution_records_no_closure() throws Exception {
        Path upstream = createUpstream(manifestWithExternal("acme/absent"));
        Registered registered = registerAndIngest(uniqueName("clo"), upstream);
        Snapshot snapshot = registered.snapshot();

        assertThat(snapshot.state()).isEqualTo(Snapshot.REJECTED);
        assertThat(snapshot.upstreamSha()).isEqualTo(snapshot.sha()).isEqualTo(headSha(upstream));
        assertThat(closureRepository.findBySnapshot(snapshot.id())).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_0164", "SVC_GW_0009"})
    void the_provenance_carries_the_served_commit_the_upstream_commit_and_the_closure() throws Exception {
        Composite composite = ingestComposite("clo");
        Snapshot snapshot = composite.snapshot();

        ApprovalService.Provenance provenance =
                approvalService.provenance(snapshot.id()).orElseThrow();

        assertThat(provenance.sha()).isEqualTo(snapshot.sha());
        assertThat(provenance.upstreamSha()).isEqualTo(composite.upstreamHead());
        assertThat(provenance.closure()).isNotNull();
        assertThat(provenance.closure().digest()).matches("^[0-9a-f]{64}$");
        assertThat(provenance.closure().members()).singleElement().satisfies(member -> {
            assertThat(member.pluginName()).isEqualTo("tools");
            assertThat(member.cloneUrl()).isEqualTo(composite.cloneUrl());
            assertThat(member.resolvedSha()).isEqualTo(FORGE.headSha("acme/tools"));
        });

        Registered local = registerAndIngest(uniqueName("clo"), createUpstream(DEFAULT_MANIFEST));
        ApprovalService.Provenance localProvenance =
                approvalService.provenance(local.snapshot().id()).orElseThrow();
        assertThat(localProvenance.sha()).isEqualTo(local.snapshot().sha());
        assertThat(localProvenance.upstreamSha()).isEqualTo(local.snapshot().sha());
        assertThat(localProvenance.closure()).isNull();
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    @SuppressWarnings("unchecked")
    void the_policy_facts_carry_the_closure() throws Exception {
        Composite composite = ingestComposite("clo");

        Map<String, Object> facts =
                factsService.build(composite.snapshot(), composite.registered().marketplace());

        Map<String, Object> snapshotFacts = (Map<String, Object>) facts.get("snapshot");
        assertThat(snapshotFacts).containsEntry("externalSources", 1);
        assertThat(snapshotFacts).containsEntry("upstreamSha", composite.upstreamHead());
        List<Map<String, Object>> plugins = (List<Map<String, Object>>) facts.get("plugins");
        Map<String, Object> tools = plugins.stream()
                .filter(plugin -> "tools".equals(plugin.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(tools).containsEntry("origin", "external");
        assertThat(tools).containsEntry("upstreamUrl", composite.cloneUrl());
        assertThat(tools).containsEntry("resolvedSha", FORGE.headSha("acme/tools"));
        Map<String, Object> hello = plugins.stream()
                .filter(plugin -> "hello".equals(plugin.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(hello).containsEntry("origin", "local");
        assertThat(hello).containsEntry("upstreamUrl", "");
        assertThat(hello).containsEntry("resolvedSha", "");
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void the_snapshots_containing_a_source_are_one_query_away() throws Exception {
        Composite composite = ingestComposite("clo");
        long id = composite.snapshot().id();
        String resolved = FORGE.headSha("acme/tools");

        assertThat(closureRepository.snapshotsContaining(composite.cloneUrl(), null))
                .contains(id);
        assertThat(closureRepository.snapshotsContaining(composite.cloneUrl(), resolved))
                .contains(id);
        assertThat(closureRepository.snapshotsContaining(composite.cloneUrl(), "0".repeat(40)))
                .doesNotContain(id);
        assertThat(closureRepository.snapshotsContaining(FORGE.baseUrl() + "/acme/other", null))
                .doesNotContain(id);
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void the_same_closure_in_two_marketplaces_has_the_same_digest() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        Path upstream = createUpstream(manifestWithExternal("acme/tools"));
        Registered first = registerAndIngest(uniqueName("clo"), upstream);
        Registered second = registerAndIngest(uniqueName("clo"), upstream);

        SnapshotClosureRepository.Closure one =
                closureRepository.findBySnapshot(first.snapshot().id()).orElseThrow();
        SnapshotClosureRepository.Closure two =
                closureRepository.findBySnapshot(second.snapshot().id()).orElseThrow();
        assertThat(one.id()).isNotEqualTo(two.id());
        assertThat(one.digest()).isEqualTo(two.digest());
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void purging_the_snapshot_removes_its_closure() throws Exception {
        Composite composite = ingestComposite("clo");
        long id = composite.snapshot().id();
        long closureId = closureRepository.findBySnapshot(id).orElseThrow().id();

        assertThat(snapshotRepository.softDelete(id, "test", Instant.now())).isPresent();
        assertThat(snapshotRepository.purge(id)).isTrue();

        assertThat(closureRepository.findBySnapshot(id)).isEmpty();
        Integer members = jdbc.sql("SELECT COUNT(*) FROM snapshot_closure_members WHERE closure_id = :id")
                .param("id", closureId)
                .query(Integer.class)
                .single();
        assertThat(members).isZero();
    }
}

package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.auth.TokenService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.vetting.VettingRepository;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The point of the whole change (GW_0103): first-party content removes a redundant system, not the
 * review. A push is quarantined, vetted and held like anything fetched, and the facade serves it
 * only after somebody approved it.
 */
class HostedLifecycleTests extends AbstractGatewayTest {

    @Autowired
    private GitStorage storage;

    @Autowired
    private MarketplaceRepository marketplaceRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private VettingRepository vettingRepository;

    private record Publisher(String marketplace, Path working, String url) {}

    private Publisher hostedPublisher(String prefix) throws Exception {
        String name = uniqueName(prefix);
        marketplaceRepository.register(name, null, null, Marketplace.ORIGIN_HOSTED, Marketplace.PUSH_APPEND_ONLY, null);
        try (Repository ignored = storage.hosted(name)) {
            // Opening creates the origin repository, as registration does in production.
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Path dir = newWorkDir("publisher");
        git(dir, "init", "--initial-branch=main");
        git(dir, "config", "user.email", "publisher@example.com");
        git(dir, "config", "user.name", "Publisher");
        String token = tokenService
                .create("alice", "publisher", List.of(), null, List.of(name))
                .token();
        return new Publisher(name, dir, publishUrl(name, token));
    }

    private void commitAndPush(Publisher publisher, String manifest, String skillBody) throws Exception {
        Path dir = publisher.working();
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(MANIFEST_PATH), manifest, StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("plugins/hello/skills/hello"));
        Files.writeString(dir.resolve("plugins/hello/skills/hello/SKILL.md"), skillBody);
        git(dir, "add", "-A");
        git(dir, "-c", "commit.gpgsign=false", "commit", "-q", "-m", "publish");
        GitResult push = git(dir, "push", publisher.url(), "main");
        assertThat(push.exitCode()).as(push.output()).isZero();
    }

    private Snapshot onlySnapshot(String marketplace) {
        long id = marketplaceRepository.findByName(marketplace).orElseThrow().id();
        List<Snapshot> snapshots = snapshotRepository.listByMarketplace(id);
        assertThat(snapshots).as("snapshots of %s", marketplace).hasSize(1);
        return snapshots.getFirst();
    }

    @Test
    @SVCs({"SVC_GW_0103"})
    void a_push_is_held_until_approved_then_served_until_revoked() throws Exception {
        Publisher publisher = hostedPublisher("lifecycle");
        commitAndPush(publisher, DEFAULT_MANIFEST, "# Hello skill\n\nFirst-party content.\n");

        // The push ingested itself: a snapshot exists, identified by the pushed commit.
        Snapshot snapshot = onlySnapshot(publisher.marketplace());
        assertThat(snapshot.state()).isEqualTo(Snapshot.HELD);
        try (Repository origin = storage.hosted(publisher.marketplace())) {
            assertThat(snapshot.sha())
                    .isEqualTo(origin.resolve(Marketplace.LINEAGE_REF).name());
        }

        // Held means held: the facade does not serve a hosted marketplace either.
        String reader = newPat();
        GitResult beforeApproval = gitClone(facadeUrl(publisher.marketplace(), reader), newWorkDir("before"));
        assertThat(beforeApproval.exitCode()).as("clone before approval").isNotZero();

        approve(snapshot.id());

        Path clone = newWorkDir("after");
        GitResult afterApproval = gitClone(facadeUrl(publisher.marketplace(), reader), clone);
        assertThat(afterApproval.exitCode()).as(afterApproval.output()).isZero();
        assertThat(Files.readString(clone.resolve("plugins/hello/skills/hello/SKILL.md")))
                .contains("First-party content.");

        // And revocation takes it off the wire exactly as it does for fetched content.
        assertThat(storage.unpublish(publisher.marketplace(), snapshot.sha())).isTrue();
        GitResult afterRevocation = gitClone(facadeUrl(publisher.marketplace(), reader), newWorkDir("revoked"));
        assertThat(afterRevocation.exitCode()).as("clone after revocation").isNotZero();
    }

    @Test
    @SVCs({"SVC_GW_0103"})
    void pushed_content_faces_the_same_vetting_chain() throws Exception {
        Publisher publisher = hostedPublisher("vetted");
        // A planted credential in first-party content is still a planted credential.
        commitAndPush(publisher, DEFAULT_MANIFEST, "# Hello skill\n\nUse AKIAIOSFODNN7EXAMPLE with this tool.\n");

        Snapshot snapshot = onlySnapshot(publisher.marketplace());
        assertThat(snapshot.state()).isEqualTo(Snapshot.HELD);
        assertThat(vettingRepository.latestRun(snapshot.id())).isPresent().get().satisfies(run -> assertThat(
                        run.verdicts())
                .as("the chain ran and found something")
                .anySatisfy(verdict -> assertThat(verdict.findings()).isNotEmpty()));
    }

    @Test
    @SVCs({"SVC_GW_0103"})
    void a_pushed_manifest_declaring_a_non_local_source_is_rejected() throws Exception {
        Publisher publisher = hostedPublisher("nonlocal");
        commitAndPush(publisher, """
                {
                  "name": "test-marketplace",
                  "owner": {"name": "Test"},
                  "plugins": [
                    {"name": "hello", "source": {"github": "acme/elsewhere"}, "description": "test"}
                  ]
                }
                """, "# Hello skill\n");

        Snapshot snapshot = onlySnapshot(publisher.marketplace());
        assertThat(snapshot.state()).isEqualTo(Snapshot.REJECTED);
        assertThat(snapshot.violation()).isNotBlank();
    }
}

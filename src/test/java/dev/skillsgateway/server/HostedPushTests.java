package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.auth.TokenService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.storage.GitStorage;
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
 * The write path (GW_0102), driven by the real git binary against the running server — the only
 * place the claim "a publisher can push, and only a publisher, and only forward" can be made
 * honestly.
 */
class HostedPushTests extends AbstractGatewayTest {

    @Autowired
    private GitStorage storage;

    @Autowired
    private MarketplaceRepository marketplaceRepository;

    @Autowired
    private TokenService tokenService;

    private String registerHosted(String name, String pushPolicy) {
        marketplaceRepository.register(name, null, null, Marketplace.ORIGIN_HOSTED, pushPolicy);
        try (Repository ignored = storage.hosted(name)) {
            return name;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String pushToken(String... marketplaces) {
        return tokenService
                .create("alice", "publisher", List.of(), null, List.of(marketplaces))
                .token();
    }

    /** A local repository with one commit on the lineage branch, ready to push. */
    private Path publisherWorkingCopy() throws Exception {
        Path dir = newWorkDir("publisher");
        git(dir, "init", "--initial-branch=main");
        git(dir, "config", "user.email", "publisher@example.com");
        git(dir, "config", "user.name", "Publisher");
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(MANIFEST_PATH), DEFAULT_MANIFEST, StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("plugins/hello/skills/hello"));
        Files.writeString(dir.resolve("plugins/hello/skills/hello/SKILL.md"), "# Hello skill\n");
        git(dir, "add", "-A");
        git(dir, "-c", "commit.gpgsign=false", "commit", "-q", "-m", "first-party content");
        return dir;
    }

    @Test
    @SVCs({"SVC_GW_0102"})
    void a_push_scoped_token_publishes_and_nothing_else_does() throws Exception {
        String name = registerHosted(uniqueName("pushok"), Marketplace.PUSH_APPEND_ONLY);
        Path working = publisherWorkingCopy();

        // Every credential that is not an explicit push scope for THIS marketplace is refused,
        // and each is refused the same way a marketplace that does not exist would be.
        String fetchScoped =
                tokenService.create("alice", "fetch-only", List.of(name), null).token();
        String wildcardFetch = newPat(); // scopes IS NULL — every marketplace, for FETCH
        String otherHosted = registerHosted(uniqueName("pushother"), Marketplace.PUSH_APPEND_ONLY);
        String scopedElsewhere = pushToken(otherHosted);

        for (String refused : List.of(fetchScoped, wildcardFetch, scopedElsewhere)) {
            GitResult result = git(working, "push", publishUrl(name, refused), "main");
            assertThat(result.exitCode())
                    .as("push with a non-push-scoped token")
                    .isNotZero();
        }

        // The one credential that should work, does.
        GitResult push = git(working, "push", publishUrl(name, pushToken(name)), "main");
        assertThat(push.exitCode()).as(push.output()).isZero();

        try (Repository origin = storage.hosted(name)) {
            assertThat(origin.resolve(Marketplace.LINEAGE_REF)).isNotNull();
        }
    }

    @Test
    @SVCs({"SVC_GW_0102"})
    void the_consumer_facade_still_accepts_no_push_at_all() throws Exception {
        String name = registerHosted(uniqueName("facadepush"), Marketplace.PUSH_APPEND_ONLY);
        Path working = publisherWorkingCopy();

        // The same token that publishes through /publish cannot push through /git: the read-only
        // facade has no receive-pack to create.
        GitResult result = git(working, "push", facadeUrl(name, pushToken(name)), "main");
        assertThat(result.exitCode()).as(result.output()).isNotZero();
    }

    @Test
    @SVCs({"SVC_GW_0102"})
    void an_unknown_marketplace_and_a_traversing_name_answer_alike() throws Exception {
        String name = registerHosted(uniqueName("probe"), Marketplace.PUSH_APPEND_ONLY);
        Path working = publisherWorkingCopy();
        String token = pushToken(name);

        for (String target : List.of("no-such-marketplace", "..", "../published/" + name)) {
            GitResult result = git(working, "push", publishUrl(target, token), "main");
            assertThat(result.exitCode()).as("push to '%s'", target).isNotZero();
        }

        // An upstream marketplace is not publishable either, however scoped the token — and it
        // stays unpublishable even when an origin repository for that name happens to exist on
        // disk, which is the only case that tells the origin check apart from "no such repo".
        String upstream = uniqueName("upstreamtarget");
        marketplaceRepository.register(upstream, "https://example.com/x.git");
        try (Repository stray = storage.hosted(upstream)) {
            assertThat(stray.getDirectory()).exists();
        }
        GitResult result = git(working, "push", publishUrl(upstream, token), "main");
        assertThat(result.exitCode())
                .as("an upstream marketplace is never a publish target")
                .isNotZero();
    }

    @Test
    @SVCs({"SVC_GW_0102"})
    void the_receive_pack_itself_refuses_deletions_and_the_hook_refuses_them_again() throws Exception {
        // Deletion is guarded twice on purpose: the ReceivePack is configured to disallow it, and
        // the hook refuses it independently. This asserts the hook alone holds, so neither guard
        // is load-bearing by itself.
        String name = registerHosted(uniqueName("doubleguard"), Marketplace.PUSH_APPEND_ONLY);
        Path working = publisherWorkingCopy();
        String url = publishUrl(name, pushToken(name));
        assertThat(git(working, "push", url, "main").exitCode()).isZero();

        assertThat(git(working, "push", url, "--delete", "main").exitCode()).isNotZero();
        try (Repository origin = storage.hosted(name)) {
            assertThat(origin.resolve(Marketplace.LINEAGE_REF)).isNotNull();
        }
    }

    @Test
    @SVCs({"SVC_GW_0102"})
    void only_the_single_lineage_may_be_published() throws Exception {
        String name = registerHosted(uniqueName("lineage"), Marketplace.PUSH_APPEND_ONLY);
        Path working = publisherWorkingCopy();
        String url = publishUrl(name, pushToken(name));

        assertThat(git(working, "push", url, "main").exitCode()).isZero();

        // A second branch is a second history: refused.
        git(working, "checkout", "-b", "experiment");
        Files.writeString(working.resolve("plugins/hello/skills/hello/SKILL.md"), "# Experimental\n");
        git(working, "add", "-A");
        git(working, "-c", "commit.gpgsign=false", "commit", "-q", "-m", "experiment");
        assertThat(git(working, "push", url, "experiment").exitCode())
                .as("a second branch")
                .isNotZero();

        // So is a tag.
        git(working, "tag", "v1");
        assertThat(git(working, "push", url, "v1").exitCode()).as("a tag").isNotZero();

        // And the lineage cannot be deleted out from under the snapshots taken from it.
        assertThat(git(working, "push", url, "--delete", "main").exitCode())
                .as("a delete")
                .isNotZero();
        try (Repository origin = storage.hosted(name)) {
            assertThat(origin.resolve(Marketplace.LINEAGE_REF)).isNotNull();
        }
    }

    @Test
    @SVCs({"SVC_GW_0102"})
    void an_append_only_marketplace_refuses_a_rewrite_and_keeps_its_tip() throws Exception {
        String name = registerHosted(uniqueName("appendonly"), Marketplace.PUSH_APPEND_ONLY);
        Path working = publisherWorkingCopy();
        String url = publishUrl(name, pushToken(name));
        assertThat(git(working, "push", url, "main").exitCode()).isZero();

        String published;
        try (Repository origin = storage.hosted(name)) {
            published = origin.resolve(Marketplace.LINEAGE_REF).name();
        }

        // Amend: same branch, different history.
        Files.writeString(working.resolve("plugins/hello/skills/hello/SKILL.md"), "# Rewritten\n");
        git(working, "add", "-A");
        git(working, "-c", "commit.gpgsign=false", "commit", "-q", "--amend", "-m", "rewritten");
        GitResult forced = git(working, "push", "--force", url, "main");

        assertThat(forced.exitCode()).as(forced.output()).isNotZero();
        try (Repository origin = storage.hosted(name)) {
            assertThat(origin.resolve(Marketplace.LINEAGE_REF).name())
                    .as("the tip a reviewer may already have approved from")
                    .isEqualTo(published);
        }
    }

    @Test
    @SVCs({"SVC_GW_0102"})
    void a_rewritable_marketplace_allows_the_rewrite_and_says_so_on_the_ledger() throws Exception {
        String name = registerHosted(uniqueName("rewritable"), Marketplace.PUSH_ALLOW_REWRITE);
        Path working = publisherWorkingCopy();
        String url = publishUrl(name, pushToken(name));
        assertThat(git(working, "push", url, "main").exitCode()).isZero();

        String before;
        try (Repository origin = storage.hosted(name)) {
            before = origin.resolve(Marketplace.LINEAGE_REF).name();
        }

        Files.writeString(working.resolve("plugins/hello/skills/hello/SKILL.md"), "# Rewritten\n");
        git(working, "add", "-A");
        git(working, "-c", "commit.gpgsign=false", "commit", "-q", "--amend", "-m", "rewritten");
        GitResult forced = git(working, "push", "--force", url, "main");
        assertThat(forced.exitCode()).as(forced.output()).isZero();

        String after;
        try (Repository origin = storage.hosted(name)) {
            after = origin.resolve(Marketplace.LINEAGE_REF).name();
        }
        assertThat(after).isNotEqualTo(before);

        // A permitted rewrite is never a silent one: both tips are on the append-only ledger.
        assertThat(fetchLogRepository.list()).anySatisfy(entry -> {
            assertThat(entry.get("event")).isEqualTo("marketplace-lineage-rewritten");
            assertThat(entry.get("marketplace")).isEqualTo(name);
            assertThat(String.valueOf(entry.get("detail"))).contains(before);
            assertThat(entry.get("sha")).isEqualTo(after);
        });
    }
}

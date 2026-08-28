package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The identity half of ADR 0008 (GW_0104): a git credential a human gets from the session they
 * already have, whose life is the gateway's to set. What makes it not a personal access token
 * reached through a different URL is precisely that the caller cannot influence its lifetime — so
 * that is what most of this asserts.
 */
class SessionCredentialTests extends AbstractGatewayTest {

    @Autowired
    private SkillsGatewayProperties properties;

    @Autowired
    private MarketplaceRepository marketplaceRepository;

    @Autowired
    private GitStorage storage;

    private String mint(String body) throws Exception {
        return mockMvc.perform(post("/api/tokens/session")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    @SVCs({"SVC_GW_0104"})
    void the_gateway_sets_the_lifetime_and_the_caller_cannot_move_it() throws Exception {
        Duration configured = properties.tokens().sessionTtl();
        Instant before = Instant.now();

        // Asking for a lifetime of one's own choosing changes nothing: the field does not exist,
        // and a caller who sends it anyway is not granted it.
        String greedy = mint("{\"name\":\"laptop\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}");
        Instant expiry = Instant.parse(JsonPath.read(greedy, "$.expiresAt"));

        assertThat(expiry)
                .as("granted expiry is the configured session TTL, not what was asked for")
                .isBetween(
                        before.plus(configured).minusSeconds(60), Instant.now().plus(configured));
        assertThat(expiry).isBefore(Instant.parse("2098-01-01T00:00:00Z"));
    }

    @Test
    @SVCs({"SVC_GW_0104"})
    void a_session_credential_is_marked_and_an_ordinary_token_is_not() throws Exception {
        String session = mint("{\"name\":\"laptop\"}");
        assertThat(JsonPath.<Boolean>read(session, "$.sessionDerived")).isTrue();
        assertThat(JsonPath.<java.util.List<String>>read(session, "$.pushScopes"))
                .as("a session credential never carries publication authority")
                .isEmpty();

        String standing = mockMvc.perform(post("/api/tokens")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ci-runner\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<Boolean>read(standing, "$.sessionDerived")).isFalse();

        // The ledger says which kind was minted; the origin is what an auditor needs.
        assertThat(fetchLogRepository.list()).anySatisfy(entry -> {
            assertThat(entry.get("event")).isEqualTo("token-created");
            assertThat(String.valueOf(entry.get("detail"))).contains("session-derived");
        });
    }

    @Test
    @SVCs({"SVC_GW_0104"})
    void it_fetches_like_any_credential_and_narrows_like_any_scope() throws Exception {
        Registered served = registerAndIngest(uniqueName("sessionfetch"), createUpstream(DEFAULT_MANIFEST));
        approve(served.snapshot().id());
        Registered other = registerAndIngest(uniqueName("sessionother"), createUpstream(DEFAULT_MANIFEST));
        approve(other.snapshot().id());

        String scoped = mint("{\"name\":\"laptop\",\"scopes\":[\"%s\"]}"
                .formatted(served.marketplace().name()));
        String token = JsonPath.read(scoped, "$.token");

        assertThat(gitClone(facadeUrl(served.marketplace().name(), token), newWorkDir("in"))
                        .exitCode())
                .as("the marketplace it names")
                .isZero();
        assertThat(gitClone(facadeUrl(other.marketplace().name(), token), newWorkDir("out"))
                        .exitCode())
                .as("a marketplace it does not name")
                .isNotZero();
    }

    @Test
    @SVCs({"SVC_GW_0104"})
    void it_cannot_publish_to_a_hosted_marketplace() throws Exception {
        String hosted = uniqueName("sessionpush");
        marketplaceRepository.register(hosted, null, null, Marketplace.ORIGIN_HOSTED, Marketplace.PUSH_APPEND_ONLY);
        try (Repository ignored = storage.hosted(hosted)) {
            assertThat(ignored.getDirectory()).exists();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        String token = JsonPath.read(mint("{\"name\":\"laptop\"}"), "$.token");

        Path working = newWorkDir("publisher");
        git(working, "init", "--initial-branch=main");
        git(working, "config", "user.email", "p@example.com");
        git(working, "config", "user.name", "P");
        Files.createDirectories(working.resolve(".claude-plugin"));
        Files.writeString(working.resolve(MANIFEST_PATH), DEFAULT_MANIFEST, StandardCharsets.UTF_8);
        git(working, "add", "-A");
        git(working, "-c", "commit.gpgsign=false", "commit", "-q", "-m", "attempt");

        GitResult push = git(working, "push", publishUrl(hosted, token), "main");
        assertThat(push.exitCode())
                .as("a credential nobody deliberately provisioned cannot publish")
                .isNotZero();
    }

    @Test
    @SVCs({"SVC_GW_0104"})
    void rotation_keeps_the_deadline_and_the_mark() throws Exception {
        String minted = mint("{\"name\":\"laptop\"}");
        long id = ((Number) JsonPath.read(minted, "$.id")).longValue();
        Instant deadline = Instant.parse(JsonPath.read(minted, "$.expiresAt"));

        mockMvc.perform(post("/api/tokens/{id}/rotate", id).with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionDerived").value(true))
                .andExpect(jsonPath("$.expiresAt").value(deadline.toString()));
    }
}

package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredMarketplace;
import dev.skillsgateway.server.config.SkillsGatewayProperties.Estate;
import dev.skillsgateway.server.estate.EstateReconciler;
import dev.skillsgateway.server.estate.EstateReconciliation;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What a client, an estate file or a publisher gets when it still asks for the hosting the gateway
 * no longer offers (GW_AUTH_0090): words naming the removal, never a field quietly ignored and never
 * a path that answers differently from every other path the gateway does not serve.
 */
class RemovedHostingRefusalTests extends AbstractGatewayTest {

    @Autowired
    private EstateReconciler reconciler;

    private ResultActions register(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/marketplaces")
                .with(oidcLogin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String problemText(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0111"})
    void a_registration_asking_for_the_hosted_origin_or_a_push_policy_is_refused_naming_the_removal() throws Exception {
        String url = createUpstream(DEFAULT_MANIFEST).toUri().toString();
        List<String> bodies = List.of(
                "{\"name\":\"%s\",\"origin\":\"hosted\"}".formatted(uniqueName("h1")),
                "{\"name\":\"%s\",\"origin\":\"hosted\",\"url\":\"%s\"}".formatted(uniqueName("h2"), url),
                "{\"name\":\"%s\",\"url\":\"%s\",\"pushPolicy\":\"allow-rewrite\"}".formatted(uniqueName("h3"), url));
        for (String body : bodies) {
            ResultActions refused = register(body).andExpect(status().isBadRequest());
            assertThat(problemText(refused))
                    .as(body)
                    .contains("hosted marketplaces were removed")
                    .contains("removing-hosted-marketplaces");
        }
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0111"})
    void the_upstream_origin_still_registers_and_no_response_carries_the_removed_fields() throws Exception {
        String name = uniqueName("up");
        String url = createUpstream(DEFAULT_MANIFEST).toUri().toString();
        String created =
                problemText(register("{\"name\":\"%s\",\"url\":\"%s\",\"origin\":\"upstream\"}".formatted(name, url))
                        .andExpect(status().isCreated()));
        assertThat(created)
                .doesNotContain("\"origin\"")
                .doesNotContain("pushPolicy")
                .doesNotContain("publishPath");

        String listing = problemText(
                mockMvc.perform(get("/api/v1/marketplaces").with(oidcLogin())).andExpect(status().isOk()));
        assertThat(listing)
                .doesNotContain("\"origin\"")
                .doesNotContain("pushPolicy")
                .doesNotContain("publishPath");

        long marketplaceId =
                marketplaceRepository.findByName(name).orElseThrow().id();
        long snapshotId = snapshotRepository
                .create(marketplaceId, "0".repeat(40), Snapshot.HELD, null, null)
                .id();
        String provenance = problemText(mockMvc.perform(
                        get("/api/v1/snapshots/{id}/provenance", snapshotId).with(oidcLogin()))
                .andExpect(status().isOk()));
        assertThat(provenance).doesNotContain("\"origin\"");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0111"})
    void a_token_request_naming_push_scopes_is_refused_and_mints_nothing() throws Exception {
        String subject = uniqueName("minter");
        var caller = oidcLogin().idToken(token -> token.subject(subject));

        ResultActions refused = mockMvc.perform(post("/api/v1/tokens")
                        .with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"publisher\",\"pushScopes\":[\"anything\"]}"))
                .andExpect(status().isBadRequest());
        assertThat(problemText(refused)).contains("hosted marketplaces were removed");
        assertThat(tokenService.list(subject)).isEmpty();

        String issued = problemText(mockMvc.perform(post("/api/v1/tokens")
                        .with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"plain\"}"))
                .andExpect(status().isCreated()));
        assertThat(issued).doesNotContain("pushScopes");
        String listing =
                problemText(mockMvc.perform(get("/api/v1/tokens").with(caller)).andExpect(status().isOk()));
        assertThat(listing).doesNotContain("pushScopes");
        assertThat(JsonPath.<List<String>>read(listing, "$[*].name")).containsExactly("plain");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0111"})
    void an_estate_entry_declaring_the_hosted_origin_or_a_push_policy_fails_naming_the_removal() throws Exception {
        String hosted = uniqueName("estatehosted");
        String policy = uniqueName("estatepolicy");
        String upstream = uniqueName("estateup");
        String url = createUpstream(DEFAULT_MANIFEST).toUri().toString();

        EstateReconciliation report = reconciler.reconcile(
                new Estate(
                        List.of(
                                new DeclaredMarketplace(hosted, null, null, "hosted", null),
                                new DeclaredMarketplace(policy, url, null, null, "allow-rewrite"),
                                new DeclaredMarketplace(upstream, url, null, "upstream", null)),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                "removed-hosting-test");

        assertThat(report.entries())
                .filteredOn(entry -> entry.name().equals(hosted) || entry.name().equals(policy))
                .hasSize(2)
                .allSatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("failed");
                    assertThat(entry.detail()).contains("hosted marketplaces were removed");
                });
        assertThat(report.entries())
                .filteredOn(entry -> entry.name().equals(upstream))
                .singleElement()
                .satisfies(entry -> assertThat(entry.action()).isEqualTo("created"));
        assertThat(marketplaceRepository.findByName(hosted)).isEmpty();
        assertThat(marketplaceRepository.findByName(policy)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0111"})
    void the_path_publishers_pushed_to_answers_like_a_path_that_never_existed() throws Exception {
        for (String credentials : List.of("none", "basic")) {
            MockHttpServletResponse old =
                    ask("/publish/some-marketplace/info/refs?service=git-receive-pack", credentials);
            MockHttpServletResponse never =
                    ask("/nothing-here/some-marketplace/info/refs?service=git-receive-pack", credentials);
            assertThat(old.getStatus()).as(credentials).isEqualTo(never.getStatus());
            assertThat(old.getHeader("WWW-Authenticate"))
                    .as(credentials)
                    .isEqualTo(never.getHeader("WWW-Authenticate"));
            assertThat(old.getHeader("Location") == null)
                    .as(credentials)
                    .isEqualTo(never.getHeader("Location") == null);
            assertThat(old.getContentType()).as(credentials).isEqualTo(never.getContentType());
        }
    }

    private MockHttpServletResponse ask(String path, String credentials) throws Exception {
        var request = get(path);
        if (credentials.equals("basic")) {
            request = request.with(httpBasic("anyone", "sgw_not-a-token"));
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        return result.getResponse();
    }
}

package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredMarketplace;
import dev.skillsgateway.server.config.SkillsGatewayProperties.Estate;
import dev.skillsgateway.server.estate.EstateReconciler;
import dev.skillsgateway.server.estate.EstateReconciliation;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Registering a marketplace the gateway itself hosts (GW_0101): no upstream, an origin repository
 * of its own, and a refresh strategy that cannot be changed because there is nothing to refresh
 * from.
 */
class HostedMarketplaceTests extends AbstractGatewayTest {

    @Autowired
    private GitStorage storage;

    @Autowired
    private EstateReconciler reconciler;

    @Test
    @SVCs({"SVC_GW_0101"})
    void a_hosted_marketplace_registers_without_a_url_and_gets_an_origin_repository() throws Exception {
        String name = uniqueName("hosted");

        String created = mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"origin\":\"hosted\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat((String) JsonPath.read(created, "$.origin")).isEqualTo(Marketplace.ORIGIN_HOSTED);
        assertThat((String) JsonPath.read(created, "$.pushPolicy")).isEqualTo(Marketplace.PUSH_APPEND_ONLY);
        assertThat(JsonPath.<Object>read(created, "$.url")).isNull();

        // The origin repository exists the moment registration returns, so a publisher can push.
        assertThat(storage.hostedIfPresent(name)).isPresent().get().satisfies(repository -> {
            assertThat(repository.getDirectory()).exists();
            repository.close();
        });

        // ... and it is neither quarantine nor published: nothing is served yet.
        assertThat(storage.publishedIfServing(name)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_0101"})
    void the_two_origins_are_mutually_exclusive_about_the_url() throws Exception {
        // A hosted marketplace with a URL is a contradiction, not an unused field.
        mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"origin\":\"hosted\",\"url\":\"https://example.com/x.git\"}"
                                .formatted(uniqueName("hostedurl"))))
                .andExpect(status().isBadRequest());

        // An upstream marketplace without one is refused exactly as before this change.
        mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(uniqueName("nourl"))))
                .andExpect(status().isBadRequest());

        // And an origin nobody defined is refused rather than silently treated as upstream.
        mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"origin\":\"mirror\"}".formatted(uniqueName("badorigin"))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @SVCs({"SVC_GW_0101"})
    void a_hosted_marketplace_reports_its_publish_path_and_refuses_a_sync_mode_change() throws Exception {
        String name = uniqueName("hostedsync");
        mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"origin\":\"hosted\",\"pushPolicy\":\"allow-rewrite\"}"
                                .formatted(name)))
                .andExpect(status().isCreated());

        String listing = mockMvc.perform(get("/api/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String path = "$[?(@.name=='" + name + "')]";
        assertThat(JsonPath.<java.util.List<String>>read(listing, path + ".publishPath"))
                .containsExactly("/publish/" + name);
        assertThat(JsonPath.<java.util.List<String>>read(listing, path + ".pushPolicy"))
                .containsExactly(Marketplace.PUSH_ALLOW_REWRITE);

        // There is no upstream to poll or be notified about: the trigger is the push.
        mockMvc.perform(put("/api/marketplaces/{name}/sync", name)
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\": \"scheduled\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @SVCs({"SVC_GW_0101"})
    void every_surface_that_would_report_an_upstream_survives_not_having_one() throws Exception {
        // A marketplace with no url is the shape most likely to NPE somewhere that assumed one.
        String name = uniqueName("nullurl");
        mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"origin\":\"hosted\"}".formatted(name)))
                .andExpect(status().isCreated());

        // The listing renders it, forge metadata resolution included (there is none to resolve).
        String listing = mockMvc.perform(get("/api/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<java.util.List<String>>read(listing, "$[?(@.name=='" + name + "')].forge"))
                .containsExactly((String) null);

        // Provenance answers without an upstream rather than failing, and says which kind it is.
        long marketplaceId =
                marketplaceRepository.findByName(name).orElseThrow().id();
        long snapshotId = snapshotRepository
                .create(marketplaceId, "0".repeat(40), dev.skillsgateway.server.persistence.Snapshot.HELD, null)
                .id();
        mockMvc.perform(get("/api/snapshots/{id}/provenance", snapshotId).with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstreamUrl").doesNotExist())
                .andExpect(jsonPath("$.origin").value("hosted"));

        // And the estate reconciler's URL-immutability check compares two nulls without dying.
        EstateReconciliation report = reconciler.reconcile(
                new Estate(
                        java.util.List.of(new DeclaredMarketplace(name, null, null, "hosted", "append-only")),
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of()),
                "null-url-test");
        assertThat(report.failed()).isZero();
        assertThat(report.entries()).anySatisfy(entry -> {
            assertThat(entry.name()).isEqualTo(name);
            assertThat(entry.action()).isEqualTo("unchanged");
        });
    }

    @Test
    @SVCs({"SVC_GW_0101"})
    void an_upstream_marketplace_is_unaffected() throws Exception {
        String name = uniqueName("stillupstream");
        String created = mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"https://example.com/x.git\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(created, "$.origin")).isEqualTo(Marketplace.ORIGIN_UPSTREAM);
        assertThat((String) JsonPath.read(created, "$.url")).isEqualTo("https://example.com/x.git");
        assertThat(storage.hostedIfPresent(name)).isEmpty();

        String listing = mockMvc.perform(get("/api/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<java.util.List<String>>read(listing, "$[?(@.name=='" + name + "')].publishPath"))
                .containsExactly((String) null);
    }
}

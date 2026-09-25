package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredMarketplace;
import dev.skillsgateway.server.config.SkillsGatewayProperties.Estate;
import dev.skillsgateway.server.estate.EstateReconciler;
import dev.skillsgateway.server.estate.EstateReconciliation;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceName;
import io.github.reqstool.annotations.SVCs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The marketplace name rule (GW_INGEST_0063, GW_INGEST_0064): at most 63 characters, the same at every
 * point a name is accepted, and a refusal that says the name is the clone path.
 */
class MarketplaceNameRuleTests extends AbstractGatewayTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** What every refusal must say, in the words a registrant reads. */
    private static final String REASON = "because it is the /git/<name> path your clients clone";

    @Autowired
    private EstateReconciler reconciler;

    /** A unique name padded with a permitted character to exactly {@code length}. */
    private static String nameOf(int length) {
        String base = uniqueName("len");
        return base + "x".repeat(length - base.length());
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0063"})
    void a_63_character_name_registers_as_an_upstream_and_as_a_hosted_marketplace() throws Exception {
        String upstream = nameOf(63);
        mockMvc.perform(register("{\"name\":\"%s\",\"url\":\"%s\"}"
                        .formatted(upstream, createUpstream(DEFAULT_MANIFEST).toUri())))
                .andExpect(status().isCreated());
        String hosted = nameOf(63);
        mockMvc.perform(register("{\"name\":\"%s\",\"origin\":\"hosted\"}".formatted(hosted)))
                .andExpect(status().isCreated());

        assertThat(marketplaceRepository.findByName(upstream)).isPresent();
        assertThat(marketplaceRepository.findByName(hosted)).isPresent();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0063", "SVC_GW_INGEST_0064"})
    void a_64_character_name_is_refused_through_the_api_and_says_why() throws Exception {
        String upstream = nameOf(64);
        mockMvc.perform(register("{\"name\":\"%s\",\"url\":\"%s\"}"
                        .formatted(upstream, createUpstream(DEFAULT_MANIFEST).toUri())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("1 to 63")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(REASON)));
        String hosted = nameOf(64);
        mockMvc.perform(register("{\"name\":\"%s\",\"origin\":\"hosted\"}".formatted(hosted)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(REASON)));

        assertThat(marketplaceRepository.findByName(upstream)).isEmpty();
        assertThat(marketplaceRepository.findByName(hosted)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0064"})
    void a_name_of_the_wrong_characters_is_refused_with_the_same_reason() throws Exception {
        mockMvc.perform(register("{\"name\":\"Bad-Name\",\"origin\":\"hosted\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(REASON)));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0063", "SVC_GW_INGEST_0064"})
    void a_64_character_declaration_fails_in_isolation_and_says_why() throws Exception {
        String tooLong = nameOf(64);
        String fits = nameOf(63);
        String url = createUpstream(DEFAULT_MANIFEST).toUri().toString();
        Estate estate = new Estate(
                List.of(
                        new DeclaredMarketplace(tooLong, url, null, null, null),
                        new DeclaredMarketplace(fits, url, null, null, null)),
                null,
                null,
                null,
                null);

        EstateReconciliation report = reconciler.reconcile(estate, "api");

        EstateReconciliation.Entry refused = entryOf(report, tooLong);
        assertThat(refused.action()).isEqualTo("failed");
        assertThat(refused.detail()).contains(REASON);
        assertThat(entryOf(report, fits).action()).isEqualTo("created");
        assertThat(marketplaceRepository.findByName(tooLong)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0063"})
    void the_marketplace_table_refuses_a_64_character_name_from_any_write_path() {
        assertThatThrownBy(() -> marketplaceRepository.register(
                        nameOf(64),
                        "https://example.invalid/m.git",
                        null,
                        Marketplace.ORIGIN_UPSTREAM,
                        Marketplace.PUSH_APPEND_ONLY,
                        null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * No row can hold a longer name once registration and the table refuse it, so this pins that
     * the facade applies the shared rule rather than showing a path that was ever open.
     */
    @Test
    @SVCs({"SVC_GW_INGEST_0063"})
    void the_facade_answers_a_64_character_name_as_not_found() throws Exception {
        String pat = newPat();
        String tooLong = nameOf(64);

        assertThat(get(facadeUrl(tooLong, null) + "/info/refs?service=git-upload-pack", pat))
                .isEqualTo(404);
        assertThat(get(publishUrl(tooLong, null) + "/info/refs?service=git-receive-pack", pat))
                .isEqualTo(404);
        mockMvc.perform(post("/status/v1/snapshots")
                        .header(HttpHeaders.AUTHORIZATION, basic(pat))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdings\":[{\"marketplace\":\"%s\",\"sha\":\"%s\"}]}"
                                .formatted(tooLong, "0".repeat(40))))
                .andExpect(status().isBadRequest());
    }

    /** The portal's mirror is the same rule in the same words, so a 422 and the form never disagree. */
    @Test
    @SVCs({"SVC_GW_INGEST_0064"})
    void the_portal_mirrors_the_rule_word_for_word() throws Exception {
        String rules = Files.readString(Path.of("src/main/frontend/src/lib/form-rules.ts"));
        assertThat(rules)
                .contains("export const MARKETPLACE_NAME = /" + MarketplaceName.REGEX + "/;")
                .contains("\"" + MarketplaceName.RULE + "\"");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(String body) {
        return post("/api/v1/marketplaces")
                .with(oidcLogin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static int get(String url, String pat) throws Exception {
        return HTTP.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header(HttpHeaders.AUTHORIZATION, basic(pat))
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static String basic(String pat) {
        return "Basic " + Base64.getEncoder().encodeToString(("token:" + pat).getBytes(StandardCharsets.UTF_8));
    }

    private static EstateReconciliation.Entry entryOf(EstateReconciliation report, String name) {
        return report.entries().stream()
                .filter(entry -> name.equals(entry.name()))
                .findFirst()
                .orElseThrow();
    }
}

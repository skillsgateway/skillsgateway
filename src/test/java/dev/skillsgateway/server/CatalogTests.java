package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.catalog.CatalogService;
import dev.skillsgateway.server.vetting.RevetService;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The global virtual catalog (GW_FACADE_0003–GW_FACADE_0005): composition from the served estate, freshness on
 * publications and revocations, provenance, audit, and the reserved name.
 *
 * <p>Its own Spring context (and so its own database): the empty-estate case needs a gateway where
 * nothing has ever served, and the revocation case needs enforcing re-vetting — a deployment
 * decision, like in {@link RevetEnforceTests}. Method order is significant: the estate grows
 * across the tests, and emptiness only exists before anything is approved.
 */
@TestPropertySource(properties = {"skills-gateway.vetting.revet.mode=enforce", "skills-gateway.catalog.name=catalog"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private static final String RULE = "aws-access-key-id";

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private RevetService revetService;

    @Autowired
    private WaiverService waiverService;

    @Test
    @Order(1)
    @SVCs({"SVC_GW_FACADE_0004"})
    void an_empty_estate_serves_an_empty_catalog_not_nothing() throws Exception {
        catalogService.rebuild();
        Path clone = newWorkDir("catempty");
        assertThat(gitClone(facadeUrl("catalog", newPat()), clone).exitCode()).isZero();
        JsonNode manifest = manifestOf(clone);
        assertThat(manifest.path("plugins")).isEmpty();
        assertThat(manifest.path("name").asText()).isEqualTo("catalog");
    }

    @Test
    @Order(2)
    @SVCs({"SVC_GW_FACADE_0003"})
    void the_catalog_aggregates_exactly_the_served_estate_under_namespaced_paths() throws Exception {
        Registered served1 = registerAndIngest(uniqueName("cata"), createUpstream(DEFAULT_MANIFEST));
        Registered served2 = registerAndIngest(uniqueName("catb"), createUpstream(DEFAULT_MANIFEST));
        Registered heldOnly = registerAndIngest(uniqueName("catheld"), createUpstream(DEFAULT_MANIFEST));
        approve(served1.snapshot().id());
        approve(served2.snapshot().id());

        // No manual rebuild: the approvals put the marketplaces into the catalog on their own.
        Path clone = newWorkDir("catagg");
        assertThat(gitClone(facadeUrl("catalog", newPat()), clone).exitCode()).isZero();

        String name1 = served1.marketplace().name();
        String name2 = served2.marketplace().name();
        assertThat(clone.resolve(name1).resolve("plugins/hello/skills/hello/SKILL.md"))
                .exists();
        assertThat(clone.resolve(name2).resolve("plugins/hello/skills/hello/SKILL.md"))
                .exists();
        assertThat(clone.resolve(heldOnly.marketplace().name())).doesNotExist();

        JsonNode manifest = manifestOf(clone);
        List<String> names = manifest.path("plugins").findValuesAsText("name");
        assertThat(names).contains(name1 + "-hello", name2 + "-hello");
        assertThat(names)
                .noneMatch(name -> name.startsWith(heldOnly.marketplace().name()));
        for (JsonNode plugin : manifest.path("plugins")) {
            String owner = plugin.path("name").asText().replaceAll("-hello$", "");
            assertThat(plugin.path("source").asText()).isEqualTo("./" + owner + "/plugins/hello");
        }
    }

    @Test
    @Order(3)
    @SVCs({"SVC_GW_FACADE_0004"})
    void publications_and_revocations_reshape_the_catalog_on_their_own() throws Exception {
        // Approved and published the only sanctioned way, then the acceptance is withdrawn.
        Registered doomed = registerAndIngest(
                uniqueName("catdoom"),
                createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRET)));
        long id = doomed.snapshot().id();
        var waiver = waiverService.create(
                id,
                RULE,
                WaiverScope.SNAPSHOT,
                null,
                "temporary acceptance",
                Instant.now().plus(Duration.ofHours(1)),
                "alice");
        approve(id);
        waiverService.revoke(waiver.id(), "alice");
        String doomedName = doomed.marketplace().name();
        String doomedSha = doomed.snapshot().sha();
        String pat = newPat();

        Path before = newWorkDir("catbefore");
        assertThat(gitClone(facadeUrl("catalog", pat), before).exitCode()).isZero();
        assertThat(before.resolve(doomedName)).exists();

        // The revocation retracts it from the catalog with no manual action.
        RevetService.RevetResult result = revetService.revetSnapshot(id, "alice");
        assertThat(result.revoked()).isTrue();

        Path after = newWorkDir("catafter");
        assertThat(gitClone(facadeUrl("catalog", pat), after).exitCode()).isZero();
        assertThat(after.resolve(doomedName)).doesNotExist();
        assertThat(manifestOf(after).path("plugins").findValuesAsText("name"))
                .noneMatch(name -> name.startsWith(doomedName));

        // Parentless history: the revoked constituent is unreachable from every advertised ref.
        assertThat(git(after, "fetch", "origin", doomedSha).exitCode()).isNotZero();

        // And a fresh approval joins the catalog on its own too.
        Registered late = registerAndIngest(uniqueName("catlate"), createUpstream(DEFAULT_MANIFEST));
        approve(late.snapshot().id());
        Path grown = newWorkDir("catgrown");
        assertThat(gitClone(facadeUrl("catalog", pat), grown).exitCode()).isZero();
        assertThat(grown.resolve(late.marketplace().name())).exists();
    }

    @Test
    @Order(4)
    @SVCs({"SVC_GW_FACADE_0005"})
    void constituents_audit_manual_rebuild_and_the_reserved_name() throws Exception {
        Registered served = registerAndIngest(uniqueName("catprov"), createUpstream(DEFAULT_MANIFEST));
        approve(served.snapshot().id());

        // The API lists exactly what a clone contains.
        String catalog = mockMvc.perform(get("/api/catalog").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode info = MAPPER.readTree(catalog);
        Path clone = newWorkDir("catprovclone");
        assertThat(gitClone(facadeUrl("catalog", newPat()), clone).exitCode()).isZero();
        for (JsonNode constituent : info.path("constituents")) {
            assertThat(clone.resolve(constituent.path("marketplace").asText())).exists();
        }
        assertThat(info.path("constituents").findValuesAsText("marketplace"))
                .contains(served.marketplace().name());
        assertThat(info.path("constituents").findValuesAsText("sha"))
                .contains(served.snapshot().sha());

        // The clone is on the ledger under the catalog's name.
        assertThat(fetchLogRepository.list()).anySatisfy(entry -> {
            assertThat(entry.get("marketplace")).isEqualTo("catalog");
            assertThat(entry.get("event")).isEqualTo("upload-pack");
        });

        // Manual rebuild works and is audited with the acting identity.
        mockMvc.perform(post("/api/catalog/rebuild").with(oidcLogin())).andExpect(status().isOk());
        assertThat(fetchLogRepository.list()).anySatisfy(entry -> {
            assertThat(entry.get("marketplace")).isEqualTo("catalog");
            assertThat(entry.get("event")).isEqualTo("catalog-rebuilt");
            assertThat(entry.get("principal")).isNotNull();
        });

        // The catalog's name is reserved: registration refuses it.
        mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"catalog\",\"url\":\"https://example.com/x.git\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    /**
     * The prefix map is not injective, and until GW_FACADE_0029 the first claimant in
     * marketplace-name order won — serving its tree under the name the loser's consumers install.
     * Fixed names rather than {@code uniqueName}, because the collision is the point: the pair has
     * to join to one string, which a random suffix cannot arrange.
     */
    @Test
    @Order(5)
    @SVCs({"SVC_GW_FACADE_0029"})
    void a_name_two_marketplaces_claim_is_published_for_neither_and_is_reported() throws Exception {
        String plain = "colx";
        String prefixed = "colx-tools";
        String contested = "colx-tools-hello";

        // colx publishes "tools-hello"; colx-tools publishes "hello". Both ask for colx-tools-hello.
        Registered first = registerAndIngest(plain, createUpstream(manifestNaming("tools-hello")));
        Registered second = registerAndIngest(prefixed, createUpstream(manifestNaming("hello")));
        Registered uncontested = registerAndIngest("colsafe", createUpstream(DEFAULT_MANIFEST));
        approve(first.snapshot().id());
        approve(second.snapshot().id());
        approve(uncontested.snapshot().id());

        Path clone = newWorkDir("catcollision");
        assertThat(gitClone(facadeUrl("catalog", newPat()), clone).exitCode()).isZero();
        List<String> published = manifestOf(clone).path("plugins").findValuesAsText("name");

        assertThat(published)
                .as("neither claimant gets it: omitting a name is legitimate, substituting content is not")
                .doesNotContain(contested);
        assertThat(published).as("a marketplace nobody contests is untouched").contains("colsafe-hello");
        // Both trees are still vendored — the withheld entry is an advertisement, not a deletion.
        assertThat(clone.resolve(plain)).exists();
        assertThat(clone.resolve(prefixed)).exists();

        // The served revision reports its own collisions, so an operator who looks afterwards finds it.
        JsonNode info = MAPPER.readTree(mockMvc.perform(get("/api/catalog").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(info.path("collisions")).anySatisfy(collision -> {
            assertThat(collision.path("name").asText()).isEqualTo(contested);
            assertThat(collision.path("marketplaces"))
                    .extracting(JsonNode::asText)
                    .containsExactly(plain, prefixed);
        });

        // And it is on the ledger, attributed to the gateway rather than to whoever approved.
        assertThat(fetchLogRepository.list()).anySatisfy(entry -> {
            assertThat(entry.get("event")).isEqualTo("catalog-name-collision");
            assertThat(entry.get("principal")).isEqualTo("catalog-builder");
            assertThat(String.valueOf(entry.get("detail"))).contains(contested);
        });
    }

    /** The default fixture with one plugin renamed, so a pair can be made to join to one name. */
    private static String manifestNaming(String plugin) {
        return DEFAULT_MANIFEST.replace("\"name\": \"hello\"", "\"name\": \"" + plugin + "\"");
    }

    private static JsonNode manifestOf(Path clone) throws Exception {
        return MAPPER.readTree(Files.readString(clone.resolve(".claude-plugin/marketplace.json")));
    }
}

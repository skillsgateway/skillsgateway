package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.vetting.Waiver;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Role enforcement in force (GW_0068–GW_0071): its own context — and therefore its own database —
 * with the switch enabled and one configuration-bootstrapped admin, so no grant here can leak
 * into the compatibility suites and vice versa.
 */
@TestPropertySource(properties = {"skills-gateway.roles.enabled=true", "skills-gateway.roles.admins=root"})
class RoleEnforcementTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WaiverService waiverService;

    /**
     * Every REST API mutation route there is, by classification. The deny-by-default walk below
     * asserts this list equals the running application's own route table, so an endpoint added
     * without a deliberate entry here — and a matching {@code require*} call — fails the suite
     * rather than shipping unprotected.
     */
    private static final Set<String> ROLE_GATED_MUTATIONS = Set.of(
            "POST /api/marketplaces",
            "PUT /api/marketplaces/{name}/sync",
            "POST /api/catalog/rebuild",
            "DELETE /api/snapshots/{id}",
            "POST /api/snapshots/{id}/restore",
            "POST /api/retention/evaluate",
            "POST /api/retention/compact",
            "POST /api/webhooks",
            "DELETE /api/webhooks/{id}",
            "POST /api/audit/sinks",
            "DELETE /api/audit/sinks/{id}",
            "PUT /api/audit/sinks/{id}/cursor",
            "POST /api/roles",
            "DELETE /api/roles/{id}",
            "POST /api/estate/reconcile",
            "POST /api/marketplaces/{name}/ingest",
            "POST /api/snapshots/{id}/approve",
            "POST /api/snapshots/{id}/reject",
            "POST /api/snapshots/{id}/revet",
            "POST /api/marketplaces/{name}/revet",
            "POST /api/snapshots/{id}/waivers",
            "DELETE /api/waivers/{id}",
            "POST /api/policy/rules",
            "PUT /api/policy/rules/{name}",
            "DELETE /api/policy/rules/{name}",
            // Read-only by contract, but a POST and approver-gated: it walks as a mutation.
            "POST /api/policy/playground");

    /** Owner-scoped by design (GW_0068): a session's own tokens need no role. */
    private static final Set<String> OWNER_SCOPED_MUTATIONS =
            Set.of("POST /api/tokens", "POST /api/tokens/{id}/rotate", "DELETE /api/tokens/{id}");

    /** The ledger and the operational listings: auditor-or-admin reads (GW_0070). */
    private static final Set<String> PRIVILEGED_READS = Set.of(
            "GET /api/audit",
            "GET /api/audit/export",
            "GET /api/audit/sinks",
            "GET /api/adoption",
            "GET /api/adoption/staleness",
            "GET /api/webhooks",
            "GET /api/webhooks/events",
            "GET /api/webhooks/deliveries",
            "GET /api/retention/candidates",
            "GET /api/roles",
            "GET /api/estate",
            "GET /api/policy/rules");

    @Test
    @SVCs({"SVC_GW_0068"})
    void a_no_role_session_is_refused_every_mutation_and_privileged_read_but_keeps_browsing_and_tokens()
            throws Exception {
        // The walk's list is asserted complete against the application's own route table first:
        // a mutation endpoint this test does not know about is a failure, not a blind spot.
        assertThat(mutationRoutesFromTheRouteTable())
                .containsExactlyInAnyOrderElementsOf(union(ROLE_GATED_MUTATIONS, OWNER_SCOPED_MUTATIONS));

        var mallory = oidcLogin().idToken(token -> token.subject("mallory"));
        for (String route : ROLE_GATED_MUTATIONS) {
            mockMvc.perform(request(route).with(mallory)).andExpect(status().isForbidden());
        }
        for (String route : PRIVILEGED_READS) {
            mockMvc.perform(request(route).with(mallory)).andExpect(status().isForbidden());
        }

        // The browsing surface stays open: it is what the portal is for.
        Registered fixture = registerAndIngest(uniqueName("rolewalk"), createUpstream(DEFAULT_MANIFEST));
        long snapshotId = fixture.snapshot().id();
        mockMvc.perform(get("/api/marketplaces").with(mallory)).andExpect(status().isOk());
        mockMvc.perform(get("/api/snapshots/{id}/content", snapshotId).with(mallory))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/snapshots/{id}/provenance", snapshotId).with(mallory))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/snapshots/{id}/vetting", snapshotId).with(mallory))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/snapshots/{id}/fetchers", snapshotId).with(mallory))
                .andExpect(status().isOk());
        mockMvc.perform(get(
                                "/api/marketplaces/{name}/waivers",
                                fixture.marketplace().name())
                        .with(mallory))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/me").with(mallory))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolesEnabled").value(true))
                .andExpect(jsonPath("$.roles").isEmpty());

        // A session's own tokens keep working end to end.
        String tokenBody = mockMvc.perform(post("/api/tokens")
                        .with(mallory)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"walk-token\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long tokenId = MAPPER.readTree(tokenBody).get("id").asLong();
        // Rotation retires the old token and issues a new one; the delete targets the successor.
        String rotated = mockMvc.perform(
                        post("/api/tokens/{id}/rotate", tokenId).with(mallory))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        mockMvc.perform(delete(
                                "/api/tokens/{id}",
                                MAPPER.readTree(rotated).get("id").asLong())
                        .with(mallory))
                .andExpect(status().isNoContent());
    }

    @Test
    @SVCs({"SVC_GW_0069"})
    void an_approver_acts_on_its_marketplace_and_is_refused_every_other_including_by_id() throws Exception {
        var root = oidcLogin().idToken(token -> token.subject("root"));
        String bobName = "bob-" + uniqueName("p");
        var bob = oidcLogin().idToken(token -> token.subject(bobName));

        String nameA = uniqueName("scope-a");
        String nameB = uniqueName("scope-b");
        Path upstreamA = createUpstream(DEFAULT_MANIFEST);
        Registered a = registerAndIngest(nameA, upstreamA);
        Registered b = registerAndIngest(nameB, createUpstream(DEFAULT_MANIFEST));

        grant(root, bobName, "approver", nameA);

        // The whole approver surface works on A...
        mockMvc.perform(post("/api/snapshots/{id}/approve", a.snapshot().id()).with(bob))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/snapshots/{id}/waivers", a.snapshot().id())
                        .with(bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleId\": \"aws-access-key-id\", \"scope\": \"PATH\","
                                + " \"path\": \"plugins/hello\", \"justification\": \"scoping test\","
                                + " \"expiresAt\": \"2036-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated());
        String waiversOfA = mockMvc.perform(
                        get("/api/marketplaces/{name}/waivers", nameA).with(bob))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long waiverOnA = MAPPER.readTree(waiversOfA).get(0).get("id").asLong();
        mockMvc.perform(delete("/api/waivers/{id}", waiverOnA).with(bob)).andExpect(status().isOk());
        mockMvc.perform(post("/api/snapshots/{id}/revet", a.snapshot().id()).with(bob))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/marketplaces/{name}/revet", nameA).with(bob)).andExpect(status().isOk());
        addUpstreamCommit(upstreamA, "reject-me");
        String heldOnA = mockMvc.perform(
                        post("/api/marketplaces/{name}/ingest", nameA).with(bob))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        mockMvc.perform(post(
                                "/api/snapshots/{id}/reject",
                                MAPPER.readTree(heldOnA).get("id").asLong())
                        .with(bob))
                .andExpect(status().isOk());

        // ...and none of it works on B — not by name, and not through a bare id (no confused
        // deputy: the owning marketplace is resolved from the addressed resource, GW_0069).
        long bSnapshot = b.snapshot().id();
        Waiver waiverOnB = waiverService.create(
                bSnapshot,
                "aws-access-key-id",
                WaiverScope.PATH,
                "plugins/hello",
                "arranged for the cross-marketplace denial",
                Instant.parse("2036-01-01T00:00:00Z"),
                "root");
        mockMvc.perform(post("/api/marketplaces/{name}/ingest", nameB).with(bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/snapshots/{id}/approve", bSnapshot).with(bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/snapshots/{id}/reject", bSnapshot).with(bob)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/snapshots/{id}/revet", bSnapshot).with(bob)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/marketplaces/{name}/revet", nameB).with(bob)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/snapshots/{id}/waivers", bSnapshot)
                        .with(bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/waivers/{id}", waiverOnB.id()).with(bob)).andExpect(status().isForbidden());
    }

    @Test
    @SVCs({"SVC_GW_0070"})
    void an_auditor_reads_the_ledger_and_listings_and_is_refused_every_mutation() throws Exception {
        var root = oidcLogin().idToken(token -> token.subject("root"));
        String carolName = "carol-" + uniqueName("p");
        var carol = oidcLogin().idToken(token -> token.subject(carolName));
        grant(root, carolName, "auditor", null);

        for (String route : PRIVILEGED_READS) {
            if (route.equals("GET /api/roles")) {
                continue; // grant administration is admin-only (GW_0071), not an auditor read
            }
            mockMvc.perform(request(route).with(carol)).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/roles").with(carol)).andExpect(status().isForbidden());
        for (String route : ROLE_GATED_MUTATIONS) {
            mockMvc.perform(request(route).with(carol)).andExpect(status().isForbidden());
        }
    }

    /**
     * The snapshot preview reads return raw held quarantine content — a step beyond the open
     * metadata reads — so they are approver-scoped (GW_0080): denied to a no-role session and to
     * an auditor, allowed to the owning approver and to an admin, and denied cross-marketplace
     * through a bare snapshot id exactly like the approver mutations (GW_0069's resolver).
     */
    @Test
    @SVCs({"SVC_GW_0080"})
    void preview_reads_are_denied_without_an_approver_grant_for_the_owning_marketplace() throws Exception {
        var root = oidcLogin().idToken(token -> token.subject("root"));
        var mallory = oidcLogin().idToken(token -> token.subject("mallory"));
        String frankName = "frank-" + uniqueName("p");
        var frank = oidcLogin().idToken(token -> token.subject(frankName));
        String graceName = "grace-" + uniqueName("p");
        var grace = oidcLogin().idToken(token -> token.subject(graceName));

        String nameA = uniqueName("preview-a");
        Registered a = registerAndIngest(nameA, createUpstream(DEFAULT_MANIFEST));
        Registered b = registerAndIngest(uniqueName("preview-b"), createUpstream(DEFAULT_MANIFEST));
        grant(root, frankName, "approver", nameA);
        grant(root, graceName, "auditor", null);

        long onA = a.snapshot().id();
        long onB = b.snapshot().id();
        // No role, and read-only auditor: refused — the auditor's charter is the ledger and the
        // listings (GW_0070), not held content.
        for (var session : List.of(mallory, grace)) {
            mockMvc.perform(get("/api/snapshots/{id}/files", onA).with(session)).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/snapshots/{id}/file", onA)
                            .param("path", MANIFEST_PATH)
                            .with(session))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/snapshots/{id}/diff", onA).with(session)).andExpect(status().isForbidden());
        }
        // The owning approver and the admin read; the same approver is refused another
        // marketplace's snapshot through its bare id.
        for (var session : List.of(frank, root)) {
            mockMvc.perform(get("/api/snapshots/{id}/files", onA).with(session)).andExpect(status().isOk());
            mockMvc.perform(get("/api/snapshots/{id}/file", onA)
                            .param("path", MANIFEST_PATH)
                            .with(session))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/snapshots/{id}/diff", onA).with(session)).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/snapshots/{id}/files", onB).with(frank)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/snapshots/{id}/file", onB)
                        .param("path", MANIFEST_PATH)
                        .with(frank))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/snapshots/{id}/diff", onB).with(frank)).andExpect(status().isForbidden());
    }

    /**
     * The playground evaluates real quarantine-backed facts, so it is scoped exactly like the
     * approval it rehearses (GW_0092): the owning approver and the admin may test expressions,
     * a foreign approver reached through the bare snapshot id is refused, and rule management
     * stays admin-only while the listing answers auditors (GW_0089).
     */
    @Test
    @SVCs({"SVC_GW_0089", "SVC_GW_0092"})
    void the_playground_is_approver_scoped_and_rule_management_admin_only() throws Exception {
        var root = oidcLogin().idToken(token -> token.subject("root"));
        String henryName = "henry-" + uniqueName("p");
        var henry = oidcLogin().idToken(token -> token.subject(henryName));
        String ivyName = "ivy-" + uniqueName("p");
        var ivy = oidcLogin().idToken(token -> token.subject(ivyName));

        String nameA = uniqueName("policy-a");
        Registered a = registerAndIngest(nameA, createUpstream(DEFAULT_MANIFEST));
        Registered b = registerAndIngest(uniqueName("policy-b"), createUpstream(DEFAULT_MANIFEST));
        grant(root, henryName, "approver", nameA);
        grant(root, ivyName, "auditor", null);

        String playground = "{\"snapshotId\": %d, \"expression\": \"true\"}"
                .formatted(a.snapshot().id());
        mockMvc.perform(post("/api/policy/playground")
                        .with(henry)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(playground))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true));
        mockMvc.perform(post("/api/policy/playground")
                        .with(root)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(playground))
                .andExpect(status().isOk());
        // The auditor reads listings, never quarantine-backed facts; the foreign snapshot is
        // refused through its bare id exactly like the approval it rehearses.
        mockMvc.perform(post("/api/policy/playground")
                        .with(ivy)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(playground))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/policy/playground")
                        .with(henry)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"snapshotId\": %d, \"expression\": \"true\"}"
                                .formatted(b.snapshot().id())))
                .andExpect(status().isForbidden());

        // Rule management is admin-only; the listing answers the auditor.
        mockMvc.perform(post("/api/policy/rules")
                        .with(henry)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"walk-rule\", \"expression\": \"true\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/policy/rules").with(ivy)).andExpect(status().isOk());
    }

    @Test
    @SVCs({"SVC_GW_0071"})
    void grants_are_admin_only_validated_audited_and_cannot_revoke_a_config_admin() throws Exception {
        var root = oidcLogin().idToken(token -> token.subject("root"));
        var dave = oidcLogin().idToken(token -> token.subject("dave-" + uniqueName("p")));
        String erin = "erin-" + uniqueName("p");
        String marketplace = uniqueName("grants");
        registerAndIngest(marketplace, createUpstream(DEFAULT_MANIFEST));

        // Not an admin: cannot grant, cannot read the grants.
        mockMvc.perform(post("/api/roles")
                        .with(dave)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantJson(erin, "admin", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/roles").with(dave)).andExpect(status().isForbidden());

        // Malformed grants are refused with the exact reasons the API documents.
        postGrant(root, grantJson(erin, "owner", null)).andExpect(status().isUnprocessableContent());
        postGrant(root, grantJson(erin, "approver", null)).andExpect(status().isUnprocessableContent());
        postGrant(root, grantJson(erin, "auditor", marketplace)).andExpect(status().isUnprocessableContent());
        postGrant(root, grantJson(erin, "approver", "does-not-exist")).andExpect(status().isNotFound());
        postGrant(root, "{\"role\": \"auditor\"}").andExpect(status().isUnprocessableContent());

        // A grant lands on the ledger; the identical grant again is a conflict.
        String created = postGrant(root, grantJson(erin, "approver", marketplace))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long grantId = MAPPER.readTree(created).get("id").asLong();
        postGrant(root, grantJson(erin, "approver", marketplace)).andExpect(status().isConflict());
        assertThat(ledgerEntries("role-granted", erin)).anySatisfy(entry -> {
            assertThat(entry.get("principal")).isEqualTo("root");
            assertThat(entry.get("marketplace")).isEqualTo(marketplace);
            assertThat(entry.get("detail")).isEqualTo("principal=%s role=approver".formatted(erin));
        });

        // The grant shows on erin's /api/me, and revocation lands on the ledger too.
        mockMvc.perform(get("/api/me").with(oidcLogin().idToken(token -> token.subject(erin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].role").value("approver"))
                .andExpect(jsonPath("$.roles[0].marketplace").value(marketplace));
        mockMvc.perform(delete("/api/roles/{id}", grantId).with(root)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/roles/{id}", grantId).with(root)).andExpect(status().isNotFound());
        assertThat(ledgerEntries("role-revoked", erin)).isNotEmpty();

        // The config-bootstrapped admin is effective but is not a grant row: there is nothing an
        // API call could revoke (GW_0071) — its admin role comes from configuration alone.
        String meAsRoot = mockMvc.perform(get("/api/me").with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolesEnabled").value(true))
                .andExpect(jsonPath("$.roles[0].role").value("admin"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(MAPPER.readTree(meAsRoot)
                        .get("roles")
                        .get(0)
                        .get("marketplace")
                        .isNull())
                .isTrue();
        String allGrants = mockMvc.perform(get("/api/roles").with(root))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        for (JsonNode grantRow : MAPPER.readTree(allGrants)) {
            assertThat(grantRow.get("principal").asText()).isNotEqualTo("root");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** All non-GET routes under /api, read from the running application's own route table. */
    private Set<String> mutationRoutesFromTheRouteTable() {
        Set<String> routes = new TreeSet<>();
        for (RequestMappingHandlerMapping mapping : webApplicationContext
                .getBeansOfType(RequestMappingHandlerMapping.class)
                .values()) {
            for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    if (method == RequestMethod.GET) {
                        continue;
                    }
                    for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                        if (pattern.startsWith("/api/")) {
                            routes.add(method.name() + " " + pattern);
                        }
                    }
                }
            }
        }
        return routes;
    }

    /** Builds the request for a "METHOD /path" walk entry, with placeholder ids and a JSON body. */
    private static MockHttpServletRequestBuilder request(String route) {
        String[] parts = route.split(" ", 2);
        String path = parts[1].replace("{id}", "999999").replace("{name}", "no-such-marketplace");
        MockHttpServletRequestBuilder builder =
                switch (parts[0]) {
                    case "POST" -> post(path);
                    case "PUT" -> put(path);
                    case "DELETE" -> delete(path);
                    case "GET" -> get(path);
                    default -> throw new IllegalArgumentException(route);
                };
        if (parts[0].equals("POST") || parts[0].equals("PUT")) {
            // A deserializable JSON body so @RequestBody binding succeeds and the request reaches
            // the authorization call — the walk must observe the require* denial, not a 400. The
            // cursor request is the one body with a primitive field, which cannot bind from {}.
            String body = path.endsWith("/cursor") ? "{\"after\": 0}" : "{}";
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return builder;
    }

    private void grant(OidcLoginRequestPostProcessor admin, String principal, String role, String marketplace)
            throws Exception {
        postGrant(admin, grantJson(principal, role, marketplace)).andExpect(status().isCreated());
    }

    private ResultActions postGrant(OidcLoginRequestPostProcessor caller, String body) throws Exception {
        return mockMvc.perform(post("/api/roles")
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String grantJson(String principal, String role, String marketplace) {
        StringBuilder json = new StringBuilder("{\"principal\": \"")
                .append(principal)
                .append("\", \"role\": \"")
                .append(role)
                .append("\"");
        if (marketplace != null) {
            json.append(", \"marketplace\": \"").append(marketplace).append("\"");
        }
        return json.append("}").toString();
    }

    private List<Map<String, Object>> ledgerEntries(String event, String targetPrincipal) {
        return fetchLogRepository.list().stream()
                .filter(entry -> event.equals(entry.get("event")))
                .filter(entry -> String.valueOf(entry.get("detail")).contains(targetPrincipal))
                .toList();
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> union = new TreeSet<>(first);
        union.addAll(second);
        return union;
    }
}

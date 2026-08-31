package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredGrant;
import dev.skillsgateway.server.config.SkillsGatewayProperties.Estate;
import dev.skillsgateway.server.estate.EstateReconciler;
import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;

/**
 * Roles derived from the identity provider's own claims (GW_0098, GW_0099), in a context whose
 * only privilege sources are one bootstrap admin and the claim mappings — so any privilege a
 * session here holds without a grant row came through the mapper.
 *
 * <p>{@code gw-approvers-ghost} maps to a marketplace that is never registered: a mapping is
 * allowed to run ahead of the estate, and must confer nothing while it does.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.roles.admins=root",
            "skills-gateway.roles.claim=groups",
            "skills-gateway.roles.mappings[0].claim-value=gw-admins",
            "skills-gateway.roles.mappings[0].role=admin",
            "skills-gateway.roles.mappings[1].claim-value=gw-approvers-a",
            "skills-gateway.roles.mappings[1].role=approver",
            "skills-gateway.roles.mappings[1].marketplace=claim-approver-mkt",
            "skills-gateway.roles.mappings[2].claim-value=gw-auditors",
            "skills-gateway.roles.mappings[2].role=auditor",
            "skills-gateway.roles.mappings[3].claim-value=gw-approvers-ghost",
            "skills-gateway.roles.mappings[3].role=approver",
            "skills-gateway.roles.mappings[3].marketplace=claim-unregistered-mkt"
        })
class ClaimRoleMappingTests extends AbstractGatewayTest {

    @Autowired
    private RoleService roleService;

    @Autowired
    private EstateReconciler reconciler;

    private static OidcLoginRequestPostProcessor session(String subject, Object groups) {
        return oidcLogin().idToken(token -> {
            token.subject(subject);
            if (groups != null) {
                token.claim("groups", groups);
            }
        });
    }

    @Test
    @SVCs({"SVC_GW_0098"})
    void a_mapped_admin_claim_grants_the_admin_surface_with_no_grant_row() throws Exception {
        var alice = session("claim-alice", List.of("gw-admins"));

        // Registration is admin-only, and so is the grants API — which would show any row.
        mockMvc.perform(post("/api/marketplaces")
                        .with(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"https://example.com/x.git\"}"
                                .formatted(uniqueName("claimadmin"))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/roles").with(alice)).andExpect(status().isOk());
        assertThat(roleService.rolesOf("claim-alice")).isEmpty();

        mockMvc.perform(get("/api/me").with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].role").value("admin"))
                .andExpect(jsonPath("$.roles[0].source").value("claim"))
                .andExpect(jsonPath("$.claimsTruncated").value(false));
    }

    @Test
    @SVCs({"SVC_GW_0098"})
    void a_mapped_approver_claim_acts_only_on_its_own_marketplace_including_through_bare_ids() throws Exception {
        var root = oidcLogin().idToken(token -> token.subject("root"));
        var bob = session("claim-bob", List.of("gw-approvers-a"));

        // The mapping names claim-approver-mkt, so the fixture must carry exactly that name.
        Registered own = registerAndIngest("claim-approver-mkt", createUpstream(DEFAULT_MANIFEST));
        Registered other = registerAndIngest(uniqueName("claimother"), createUpstream(DEFAULT_MANIFEST));

        mockMvc.perform(post("/api/snapshots/{id}/approve", own.snapshot().id()).with(bob))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/marketplaces/{name}/ingest", "claim-approver-mkt")
                        .with(bob))
                .andExpect(status().isCreated());

        // Another marketplace is refused by name and through a bare snapshot id (GW_0069).
        mockMvc.perform(post(
                                "/api/marketplaces/{name}/ingest",
                                other.marketplace().name())
                        .with(bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/snapshots/{id}/approve", other.snapshot().id())
                        .with(bob))
                .andExpect(status().isForbidden());

        // ... and through a bare waiver id belonging to the other marketplace.
        String waiver = mockMvc.perform(
                        post("/api/snapshots/{id}/waivers", other.snapshot().id())
                                .with(root)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ruleId\": \"aws-access-key-id\", \"scope\": \"PATH\","
                                        + " \"path\": \"plugins/hello\", \"justification\": \"claim scoping test\","
                                        + " \"expiresAt\": \"2036-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        mockMvc.perform(delete("/api/waivers/{id}", ((Number) JsonPath.read(waiver, "$.id")).longValue())
                        .with(bob))
                .andExpect(status().isForbidden());
    }

    @Test
    @SVCs({"SVC_GW_0098"})
    void a_mapped_auditor_claim_reads_the_ledger_and_is_refused_every_mutation() throws Exception {
        var carol = session("claim-carol", List.of("gw-auditors"));

        mockMvc.perform(get("/api/audit").with(carol)).andExpect(status().isOk());
        mockMvc.perform(get("/api/webhooks").with(carol)).andExpect(status().isOk());
        mockMvc.perform(get("/api/retention/candidates").with(carol)).andExpect(status().isOk());
        mockMvc.perform(post("/api/marketplaces")
                        .with(carol)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"auditor-denied\",\"url\":\"https://example.com/x.git\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/catalog/rebuild").with(carol)).andExpect(status().isForbidden());
    }

    @Test
    @SVCs({"SVC_GW_0098"})
    void claim_values_that_only_resemble_a_mapping_grant_nothing() throws Exception {
        List<String> nearMisses =
                List.of("gw-admin", "gw-admins-extra", "xgw-admins", "GW-ADMINS", "Gw-Admins", "gw admins", "");
        for (String value : nearMisses) {
            var mallory = session("claim-mallory-" + Integer.toHexString(value.hashCode()), List.of(value));
            mockMvc.perform(get("/api/roles").with(mallory)).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/me").with(mallory))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles").isEmpty());
        }

        // Surrounding whitespace is the one thing YAML hides, so it is trimmed rather than kept.
        mockMvc.perform(get("/api/roles").with(session("claim-padded", List.of("  gw-admins  "))))
                .andExpect(status().isOk());
    }

    @Test
    @SVCs({"SVC_GW_0098"})
    void a_credential_without_identity_provider_claims_derives_nothing() throws Exception {
        // The dev-insecure principal shape: a bare-string principal with forged authorities, so
        // there is no claim map to read and nothing to match against.
        Authentication forged = UsernamePasswordAuthenticationToken.authenticated(
                "claim-forged", null, AuthorityUtils.createAuthorityList("ROLE_USER", "gw-admins"));
        assertThat(roleService.effectiveRoles(forged)).isEmpty();

        // An OIDC session carrying the values under a claim the gateway was not told to read.
        var decoy = oidcLogin().idToken(token -> token.subject("claim-decoy").claim("roles", List.of("gw-admins")));
        mockMvc.perform(get("/api/roles").with(decoy)).andExpect(status().isForbidden());
    }

    @Test
    @SVCs({"SVC_GW_0098"})
    void claim_roles_union_with_configured_admins_and_stored_grants() throws Exception {
        var root = oidcLogin().idToken(token -> token.subject("root"));

        // A configuration admin that also carries a mapped auditor claim holds both, each named.
        List<Map<String, Object>> rootRoles = rolesOnMe(session("root", List.of("gw-auditors")));
        assertThat(rootRoles).anySatisfy(role -> {
            assertThat(role.get("role")).isEqualTo("admin");
            assertThat(role.get("source")).isEqualTo("config");
        });
        assertThat(rootRoles).anySatisfy(role -> {
            assertThat(role.get("role")).isEqualTo("auditor");
            assertThat(role.get("source")).isEqualTo("claim");
        });

        String dave = "claim-dave";
        mockMvc.perform(post("/api/roles")
                        .with(root)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principal\": \"" + dave + "\", \"role\": \"auditor\"}"))
                .andExpect(status().isCreated());

        // Auditor from both a row and a claim is reported once, attributed to the row.
        List<Map<String, Object>> daveRoles = rolesOnMe(session(dave, List.of("gw-auditors", "gw-admins")));
        assertThat(daveRoles).hasSize(2);
        assertThat(daveRoles).anySatisfy(role -> {
            assertThat(role.get("role")).isEqualTo("auditor");
            assertThat(role.get("source")).isEqualTo("grant");
        });
        assertThat(daveRoles).anySatisfy(role -> {
            assertThat(role.get("role")).isEqualTo("admin");
            assertThat(role.get("source")).isEqualTo("claim");
        });
    }

    @Test
    @SVCs({"SVC_GW_0098"})
    void a_mapping_naming_an_unregistered_marketplace_starts_and_matches_nothing() throws Exception {
        // The context booted with a mapping for claim-unregistered-mkt; reaching this line is
        // half the claim, and conferring nothing anywhere is the other half.
        assertThat(marketplaceRepository.findByName("claim-unregistered-mkt")).isEmpty();
        var ghost = session("claim-ghost", List.of("gw-approvers-ghost"));
        Registered fixture = registerAndIngest(uniqueName("ghosttarget"), createUpstream(DEFAULT_MANIFEST));
        mockMvc.perform(post("/api/snapshots/{id}/approve", fixture.snapshot().id())
                        .with(ghost))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/me").with(ghost))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].role").value("approver"))
                .andExpect(jsonPath("$.roles[0].marketplace").value("claim-unregistered-mkt"));
    }

    @Test
    @SVCs({"SVC_GW_0085"})
    void a_declared_grant_is_still_created_for_a_principal_who_holds_the_role_by_claim() {
        // The reconciler's "already converged" check reads stored grants only. If a claim-derived
        // role counted as convergence, the declared row would never be written — and losing the
        // group would then silently lose the grant too.
        String principal = "claim-estate";
        assertThat(roleService.rolesOf(principal)).isEmpty();

        reconciler.reconcile(
                new Estate(
                        List.of(),
                        List.of(new DeclaredGrant(principal, "admin", null)),
                        List.of(),
                        List.of(),
                        List.of()),
                "claim-mapping-test");

        assertThat(roleService.rolesOf(principal))
                .containsExactly(new RoleService.EffectiveRole("admin", null, RoleService.EffectiveRole.GRANT));
    }

    @Test
    @SVCs({"SVC_GW_0099"})
    void a_truncated_claim_is_reported_and_an_absent_one_is_not() throws Exception {
        // The overage shape: no groups claim, but the provider says there would have been one.
        var overflowing =
                oidcLogin().idToken(token -> token.subject("claim-overflow").claim("hasgroups", true));
        mockMvc.perform(get("/api/me").with(overflowing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimsTruncated").value(true))
                .andExpect(jsonPath("$.roles").isEmpty());
        mockMvc.perform(get("/api/roles").with(overflowing)).andExpect(status().isForbidden());

        // The distributed-claims shape says the same thing a different way.
        var distributed = oidcLogin()
                .idToken(token -> token.subject("claim-distributed").claim("_claim_names", Map.of("groups", "src1")));
        mockMvc.perform(get("/api/me").with(distributed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimsTruncated").value(true));

        // A session that genuinely has no memberships is unprivileged, not truncated.
        mockMvc.perform(get("/api/me").with(session("claim-nobody", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimsTruncated").value(false))
                .andExpect(jsonPath("$.roles").isEmpty());
        mockMvc.perform(get("/api/me").with(session("claim-noclaim", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimsTruncated").value(false));
    }

    private List<Map<String, Object>> rolesOnMe(OidcLoginRequestPostProcessor caller) throws Exception {
        String body = mockMvc.perform(get("/api/me").with(caller))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.roles");
    }
}

package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.auth.TokenService;
import dev.skillsgateway.server.estate.EstateReconciler;
import dev.skillsgateway.server.persistence.ActorType;
import dev.skillsgateway.server.roles.RoleGrant;
import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Effective authority is the <b>intersection</b> — the allowlist ∧ the credential's named scopes ∧
 * the principal's roles — never any two of them (GW_0130). Its own context, with role enforcement
 * genuinely enabled, so the one-sided cases below can actually be observed: with the flag at its
 * default of off, every {@code require*} passes and the role half of the intersection is invisible.
 */
@TestPropertySource(properties = {"skills-gateway.roles.enabled=true", "skills-gateway.roles.admins=owner"})
class MachineRoleIntersectionTests extends AbstractGatewayTest {

    @Autowired
    private RoleService roleService;

    @Autowired
    private EstateReconciler estateReconciler;

    private TokenService.IssuedToken credential(String principal, List<String> scopes) {
        return tokenService.createMachineCredential(
                principal, "intersection", scopes, Instant.now().plus(10, ChronoUnit.DAYS), "owner");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder bearer(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String secret) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + secret);
    }

    @Test
    @SVCs({"SVC_GW_0130"})
    void a_scope_without_the_role_is_refused_and_so_is_the_role_without_the_scope() throws Exception {
        // Scope but no role: the allowlist admits it, RoleService does not.
        String unroled = uniqueName("scoped-only");
        TokenService.IssuedToken scopedOnly = credential(unroled, List.of("audit:read"));
        mockMvc.perform(bearer(get("/api/audit"), scopedOnly.token())).andExpect(status().isForbidden());

        // Role but no scope: the role is irrelevant, because the allowlist never let it through.
        String unscoped = uniqueName("roled-only");
        roleService.grant(unscoped, RoleGrant.AUDITOR, null, "owner");
        TokenService.IssuedToken roledOnly = credential(unscoped, List.of("marketplaces:read"));
        mockMvc.perform(bearer(get("/api/audit"), roledOnly.token())).andExpect(status().isForbidden());

        // Both: and only then does it work, which is what makes this an intersection.
        String both = uniqueName("both");
        roleService.grant(both, RoleGrant.AUDITOR, null, "owner");
        TokenService.IssuedToken complete = credential(both, List.of("audit:read"));
        mockMvc.perform(bearer(get("/api/audit"), complete.token())).andExpect(status().isOk());
    }

    @Test
    @SVCs({"SVC_GW_0130"})
    void an_admin_granted_machine_credential_still_cannot_grant_a_role_or_approve() throws Exception {
        String principal = uniqueName("privileged");
        roleService.grant(principal, RoleGrant.ADMIN, null, "owner");
        TokenService.IssuedToken everything =
                credential(principal, List.copyOf(dev.skillsgateway.server.auth.MachineApiRegistry.scopes()));

        // The role is real: with it, a scoped read it holds succeeds.
        mockMvc.perform(bearer(get("/api/roles"), everything.token())).andExpect(status().isOk());
        // And still nothing on the unreachable table, because no scope value reaches it.
        mockMvc.perform(bearer(post("/api/roles"), everything.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principal\": \"mallory\", \"role\": \"admin\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(bearer(post("/api/snapshots/{id}/approve", 999999), everything.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(bearer(post("/api/tokens/machine"), everything.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    /**
     * The declared estate is the only route to a machine role, and it needs no new mechanism: a
     * principal is just a string, so {@code estate.grants} can name a credential's principal
     * today. Reconciliation attributes it to the gateway itself.
     */
    @Test
    @SVCs({"SVC_GW_0130"})
    void the_declared_estate_can_grant_a_role_to_a_machine_principal() {
        String principal = uniqueName("declared");
        credential(principal, List.of("audit:read"));

        roleService.grant(principal, RoleGrant.AUDITOR, null, EstateReconciler.ACTOR);

        assertThat(roleService.rolesOf(principal))
                .extracting(RoleService.EffectiveRole::role)
                .contains(RoleGrant.AUDITOR);
        // Attributed to the gateway acting on its own, with no credential in the pipeline at all.
        List<Map<String, Object>> entries = fetchLogRepository.listByActorType(ActorType.SYSTEM).stream()
                .filter(row -> EstateReconciler.ACTOR.equals(row.get("principal")))
                .filter(row -> "role-granted".equals(row.get("event")))
                .filter(row -> String.valueOf(row.get("detail")).contains(principal))
                .toList();
        assertThat(entries).hasSize(1);
    }
}

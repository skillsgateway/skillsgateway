package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.auth.TokenService;
import dev.skillsgateway.server.persistence.ActorType;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * The ledger's explicit actor kind (GW_0128). The vocabulary existed before this column did — as
 * three magic strings in the identity column, distinguishable only by string comparison against
 * values that also look like ordinary principals. What changes here is that no consumer compares
 * strings at all.
 */
@TestPropertySource(
        // Authorization is always enforced (GW_0138), so this suite names every principal it acts
        // as -- the human and each machine credential. The machine principals are fixed rather than
        // unique because this suite has its own context, and therefore its own database.
        properties = {
            "skills-gateway.roles.admins=a-person,ledger-machine,ledger-ephemeral,ledger-owned,ledger-reader,ledger-exporter,ledger-machine-kinds,ledger-person"
        })
class MachineLedgerTests extends AbstractGatewayTest {

    @Autowired
    private JdbcClient jdbc;

    private TokenService.IssuedToken credential(String principal, List<String> scopes) {
        return tokenService.createMachineCredential(
                principal, "ledger-suite", scopes, Instant.now().plus(30, ChronoUnit.DAYS), "admin@example.invalid");
    }

    private List<Map<String, Object>> entriesFor(String principal) {
        return fetchLogRepository.list().stream()
                .filter(row -> principal.equals(row.get("principal")))
                .toList();
    }

    @Test
    @SVCs({"SVC_GW_0128"})
    void a_machine_credentials_entries_carry_its_actor_kind_its_name_and_its_credential_id() throws Exception {
        String principal = "ledger-machine";
        TokenService.IssuedToken machine = credential(principal, List.of("roles:read"));

        mockMvc.perform(get("/api/roles").header(HttpHeaders.AUTHORIZATION, "Bearer " + machine.token()))
                .andExpect(status().isOk());

        List<Map<String, Object>> entries = entriesFor(principal);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsEntry("actor_type", ActorType.MACHINE.value());
        assertThat(entries.get(0)).containsEntry("event", "roles-read");
        // Per-credential resolution for a leak trace, which admin entries do not have today.
        assertThat(entries.get(0).get("token_id")).isEqualTo(machine.id());
    }

    @Test
    @SVCs({"SVC_GW_0128"})
    void the_ledger_separates_actor_kinds_without_string_parsing_or_a_join() throws Exception {
        String principal = "ledger-machine-kinds";
        TokenService.IssuedToken machine = credential(principal, List.of("roles:read"));
        mockMvc.perform(get("/api/roles").header(HttpHeaders.AUTHORIZATION, "Bearer " + machine.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/roles").with(oidcLogin().idToken(token -> token.subject("a-person"))))
                .andExpect(status().isOk());

        List<Map<String, Object>> machineEntries = fetchLogRepository.listByActorType(ActorType.MACHINE);
        List<Map<String, Object>> humanEntries = fetchLogRepository.listByActorType(ActorType.HUMAN);

        assertThat(machineEntries).extracting(row -> row.get("principal")).contains(principal);
        assertThat(humanEntries).extracting(row -> row.get("principal")).contains("a-person");
        // Disjoint, by the column rather than by anybody's parsing rule.
        assertThat(machineEntries)
                .extracting(row -> row.get("id"))
                .doesNotContainAnyElementsOf(
                        humanEntries.stream().map(row -> row.get("id")).toList());
        assertThat(humanEntries).extracting(row -> row.get("principal")).doesNotContain(principal);
    }

    /**
     * The whole argument for denormalising the column, made executable: a ledger row written years
     * ago must still say what it meant after the credential it names has been revoked and its row
     * deleted. A join to {@code access_tokens} could not survive this.
     */
    @Test
    @SVCs({"SVC_GW_0128"})
    void a_ledger_row_still_reports_its_actor_after_the_credential_it_names_is_deleted() throws Exception {
        String principal = "ledger-ephemeral";
        TokenService.IssuedToken machine = credential(principal, List.of("roles:read"));
        mockMvc.perform(get("/api/roles").header(HttpHeaders.AUTHORIZATION, "Bearer " + machine.token()))
                .andExpect(status().isOk());

        assertThat(tokenService.revokeMachineCredential(machine.id())).isTrue();
        assertThat(jdbc.sql("DELETE FROM access_tokens WHERE id = :id")
                        .param("id", machine.id())
                        .update())
                .isOne();

        List<Map<String, Object>> entries = entriesFor(principal);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsEntry("actor_type", ActorType.MACHINE.value());
        assertThat(entries.get(0).get("token_id")).isEqualTo(machine.id());
    }

    @Test
    @SVCs({"SVC_GW_0128"})
    void no_entry_a_machine_credential_produces_names_the_provisioning_human() throws Exception {
        String principal = "ledger-owned";
        TokenService.IssuedToken machine = credential(principal, List.of("roles:read"));
        mockMvc.perform(get("/api/roles").header(HttpHeaders.AUTHORIZATION, "Bearer " + machine.token()))
                .andExpect(status().isOk());

        assertThat(entriesFor(principal))
                .as("the credential acts as itself, never as the person who provisioned it")
                .allSatisfy(row -> assertThat(row.get("principal")).isEqualTo(principal));
        // The responsible person is named once, on the credential itself, rather than
        // impersonated on every use.
        assertThat(tokenService
                        .findMachineCredential(machine.id())
                        .orElseThrow()
                        .machineOwner())
                .isEqualTo("admin@example.invalid");
    }

    @Test
    @SVCs({"SVC_GW_0128"})
    void every_authorized_read_of_the_role_grants_writes_exactly_one_entry_of_its_own_kind() throws Exception {
        String principal = "ledger-reader";
        TokenService.IssuedToken machine = credential(principal, List.of("roles:read"));

        mockMvc.perform(get("/api/roles").header(HttpHeaders.AUTHORIZATION, "Bearer " + machine.token()))
                .andExpect(status().isOk());
        assertThat(entriesFor(principal)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("event", "roles-read");
            assertThat(row).containsEntry("actor_type", ActorType.MACHINE.value());
        });

        // The rule is uniform: a person reading the same endpoint writes an entry too. The
        // asymmetry an earlier draft proposed would have left a compromised session reading the
        // grants untraced, and actor_type is what separates the two at query time — not which
        // rows happen to exist.
        String human = "ledger-person";
        mockMvc.perform(get("/api/roles").with(oidcLogin().idToken(token -> token.subject(human))))
                .andExpect(status().isOk());
        assertThat(entriesFor(human)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("event", "roles-read");
            assertThat(row).containsEntry("actor_type", ActorType.HUMAN.value());
        });
    }

    /**
     * Reading the ledger must not append to the ledger. An exporter polling on a cursor loop would
     * otherwise append one entry per poll, and that entry is itself new content to export: it
     * converges rather than exploding, but every deployment with a polling exporter grows a
     * permanent floor of self-referential rows.
     */
    @Test
    @SVCs({"SVC_GW_0128"})
    void a_machine_read_of_the_ledger_writes_no_entry() throws Exception {
        String principal = "ledger-exporter";
        TokenService.IssuedToken machine = credential(principal, List.of("audit:read"));
        int before = fetchLogRepository.list().size();

        mockMvc.perform(get("/api/audit").header(HttpHeaders.AUTHORIZATION, "Bearer " + machine.token()))
                .andExpect(status().isOk());

        assertThat(fetchLogRepository.list()).hasSize(before);
        assertThat(entriesFor(principal)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_0128"})
    void an_unauthorized_read_of_the_role_grants_writes_no_entry() throws Exception {
        String principal = uniqueName("unscoped-reader");
        // A machine credential scoped elsewhere: the allowlist refuses it before the controller.
        TokenService.IssuedToken machine = credential(principal, List.of("marketplaces:read"));

        mockMvc.perform(get("/api/roles").header(HttpHeaders.AUTHORIZATION, "Bearer " + machine.token()))
                .andExpect(status().isForbidden());

        assertThat(entriesFor(principal)).isEmpty();
    }
}

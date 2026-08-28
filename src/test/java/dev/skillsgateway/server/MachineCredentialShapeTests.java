package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.persistence.AccessToken;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The three scope dimensions and their three different empty meanings (GW_0126). One record now
 * serves two audiences, and the next bug lives in whichever empty list somebody reads as
 * "unrestricted", so all three defaults are asserted together rather than one at a time.
 */
class MachineCredentialShapeTests {

    private static AccessToken token(String scopes, String pushScopes, String apiScopes) {
        return new AccessToken(
                1L,
                "principal",
                "name",
                "hash",
                Instant.now(),
                null,
                scopes,
                null,
                null,
                pushScopes,
                false,
                apiScopes,
                null);
    }

    @Test
    @SVCs({"SVC_GW_0126"})
    void a_credential_with_no_api_scopes_grants_no_api_reach() {
        AccessToken existing = token(null, null, null);

        assertThat(existing.apiScopeList()).isEmpty();
        assertThat(existing.machineCredential()).isFalse();
        assertThat(existing.permitsApiScope("marketplaces:read")).isFalse();
        assertThat(existing.permitsApiScope("roles:read")).isFalse();
        // A blank string is the same absence: a column trimmed to empty must not become a grant.
        assertThat(token(null, null, "").apiScopeList()).isEmpty();
        assertThat(token(null, null, "  ").machineCredential()).isFalse();
    }

    @Test
    @SVCs({"SVC_GW_0126"})
    void the_three_empty_list_defaults_mean_three_different_things() {
        AccessToken empty = token(null, null, null);

        assertThat(empty.permitsMarketplace("anything"))
                .as("fetch: empty means every marketplace")
                .isTrue();
        assertThat(empty.permitsPushTo("anything"))
                .as("push: empty means nowhere")
                .isFalse();
        assertThat(empty.permitsApiScope("estate:read"))
                .as("api: empty means nothing")
                .isFalse();
    }

    /**
     * Task 6.5a. The headline hole: {@code permitsMarketplace} returned {@code scopeList.isEmpty()
     * || scopeList.contains(marketplace)}, so a credential minted with only administrative scopes
     * — which necessarily has an empty fetch list — would authenticate on the facade and fetch the
     * entire estate. That is the exact opposite of the guarantee this change exists to make.
     */
    @Test
    @SVCs({"SVC_GW_0127"})
    void an_api_only_credential_with_an_empty_fetch_list_reaches_no_marketplace() {
        AccessToken machine = token(null, null, "marketplaces:read");

        assertThat(machine.scopeList())
                .as("a machine credential's fetch list is empty")
                .isEmpty();
        assertThat(machine.permitsMarketplace("anything")).isFalse();
        assertThat(machine.permitsMarketplace("catalog")).isFalse();
    }

    /**
     * Task 6.5c, the regression guard on the fix above: the permissive fetch default is preserved
     * for every credential that holds no administrative scope, which is every credential that
     * exists today.
     */
    @Test
    @SVCs({"SVC_GW_0064"})
    void an_ordinary_fetch_token_with_an_empty_fetch_list_still_reaches_every_marketplace() {
        AccessToken fetchOnly = token(null, null, null);

        assertThat(fetchOnly.permitsMarketplace("anything")).isTrue();
        assertThat(fetchOnly.permitsMarketplace("catalog")).isTrue();
        // And a fetch credential that also names marketplaces is unchanged either way.
        assertThat(token("alpha", null, null).permitsMarketplace("alpha")).isTrue();
        assertThat(token("alpha", null, null).permitsMarketplace("beta")).isFalse();
    }

    @Test
    @SVCs({"SVC_GW_0126"})
    void an_api_scope_list_is_read_as_the_named_values_and_nothing_else() {
        AccessToken machine = token(null, null, "marketplaces:read,estate:read");

        assertThat(machine.machineCredential()).isTrue();
        assertThat(machine.apiScopeList()).containsExactly("marketplaces:read", "estate:read");
        assertThat(machine.permitsApiScope("marketplaces:read")).isTrue();
        assertThat(machine.permitsApiScope("estate:read")).isTrue();
        // No scope implies another, and there is no wildcard.
        assertThat(machine.permitsApiScope("marketplaces:register")).isFalse();
        assertThat(machine.permitsApiScope("*")).isFalse();
    }
}

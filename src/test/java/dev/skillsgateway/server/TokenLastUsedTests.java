package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.auth.TokenService;
import dev.skillsgateway.server.persistence.AccessToken;
import dev.skillsgateway.server.persistence.TokenRepository;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Last use of a credential (GW_AUTH_0031), on both chains.
 *
 * <p>The positive half is ordinary. The half worth the suite is the negative one: {@code
 * last_used_at} is read by an operator as evidence that a credential was <em>accepted</em>, so
 * anything that stamps it on a refused attempt turns the field into a lie about an authentication
 * that never happened. The machine chain is where that is easy to get wrong — it resolves a live
 * row and <em>then</em> refuses anything without administrative scope — so the fetch-token-as-bearer
 * case is asserted here rather than left to the shape of the code.
 */
class TokenLastUsedTests extends AbstractGatewayTest {

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private JdbcClient jdbc;

    private static Instant soon() {
        return Instant.now().plus(30, ChronoUnit.DAYS);
    }

    private TokenService.IssuedToken machineCredential() {
        return tokenService.createMachineCredential(
                uniqueName("lastused-machine"), "last-used", List.of("marketplaces:read"), soon(), "root");
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String secret) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + secret);
    }

    private Instant lastUsed(long id) {
        return tokenRepository.findById(id).map(AccessToken::lastUsedAt).orElse(null);
    }

    /** Backdates the stamp so the once-a-minute bound can be crossed without waiting a minute. */
    private void ageLastUsed(long id, long minutes) {
        int updated = jdbc.sql("UPDATE access_tokens SET last_used_at = :then WHERE id = :id")
                .param("then", OffsetDateTime.now().minusMinutes(minutes))
                .param("id", id)
                .update();
        assertThat(updated).isOne();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0031"})
    void a_facade_fetch_stamps_the_token_and_the_listing_reports_it() throws Exception {
        String name = uniqueName("lastused");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());
        TokenService.IssuedToken token = tokenService.create("alice", "last-used-pat");
        assertThat(lastUsed(token.id())).as("a freshly issued token has never been used").isNull();

        Instant before = Instant.now();
        GitResult clone = gitClone(facadeUrl(name, token.token()), newWorkDir("lastused-clone"));
        assertThat(clone.exitCode()).as(clone.output()).isZero();

        Instant stamped = lastUsed(token.id());
        assertThat(stamped).isNotNull();
        assertThat(stamped).isAfterOrEqualTo(before.minusSeconds(1));

        // And it reaches the owner's own listing, which is the surface the portal reads.
        String body = mockMvc.perform(get("/api/tokens").with(oidcLogin().idToken(id -> id.subject("alice"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> reported = JsonPath.read(body, "$[?(@.id == %d)].lastUsedAt".formatted(token.id()));
        assertThat(reported).singleElement().isNotNull();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0031"})
    void a_machine_api_call_stamps_the_credential_and_the_administrative_listing_reports_it() throws Exception {
        TokenService.IssuedToken credential = machineCredential();
        assertThat(lastUsed(credential.id())).isNull();

        mockMvc.perform(bearer(get("/api/marketplaces"), credential.token())).andExpect(status().isOk());

        assertThat(lastUsed(credential.id())).isNotNull();

        String body = mockMvc.perform(
                        get("/api/tokens/machine").with(oidcLogin().idToken(id -> id.subject("root"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> reported =
                JsonPath.read(body, "$[?(@.id == %d)].lastUsedAt".formatted(credential.id()));
        assertThat(reported).singleElement().isNotNull();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0031"})
    void the_stamp_is_rewritten_at_most_once_a_minute() throws Exception {
        TokenService.IssuedToken credential = machineCredential();

        mockMvc.perform(bearer(get("/api/marketplaces"), credential.token())).andExpect(status().isOk());
        Instant first = lastUsed(credential.id());
        assertThat(first).isNotNull();

        // Within the bound: the credential is used again and the recorded instant does not move.
        // This is the whole point of the throttle — the hot path costs a statement that writes
        // nothing rather than a row write per request.
        mockMvc.perform(bearer(get("/api/marketplaces"), credential.token())).andExpect(status().isOk());
        assertThat(lastUsed(credential.id())).isEqualTo(first);

        // Past the bound: the next use advances it, so a credential in continuous use never looks
        // stale.
        ageLastUsed(credential.id(), 5);
        Instant aged = lastUsed(credential.id());
        mockMvc.perform(bearer(get("/api/marketplaces"), credential.token())).andExpect(status().isOk());
        assertThat(lastUsed(credential.id())).isAfter(aged);
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0031"})
    void a_refused_authentication_stamps_nothing() throws Exception {
        String name = uniqueName("refused");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());

        // An unrecognised secret: there is no row to stamp, and nothing may be created for one.
        GitResult unknown = gitClone(facadeUrl(name, "sgw_definitely-not-a-token"), newWorkDir("refused-unknown"));
        assertThat(unknown.exitCode()).as(unknown.output()).isNotZero();

        // A revoked token. Revocation is applied by the lookup, so this never reaches the provider
        // — asserted rather than assumed, because "the lookup already filters it" is exactly the
        // kind of claim that stops being true when the lookup changes.
        TokenService.IssuedToken revoked = tokenService.create("alice", "revoked-last-used");
        assertThat(tokenService.revoke(revoked.id(), "alice")).isTrue();
        GitResult revokedClone = gitClone(facadeUrl(name, revoked.token()), newWorkDir("refused-revoked"));
        assertThat(revokedClone.exitCode()).as(revokedClone.output()).isNotZero();
        assertThat(lastUsed(revoked.id())).isNull();

        // An expired token, refused by the same lookup for the other reason.
        TokenService.IssuedToken expired =
                tokenService.create("alice", "expired-last-used", List.of(), Instant.now().plusMillis(1));
        Thread.sleep(20);
        GitResult expiredClone = gitClone(facadeUrl(name, expired.token()), newWorkDir("refused-expired"));
        assertThat(expiredClone.exitCode()).as(expiredClone.output()).isNotZero();
        assertThat(lastUsed(expired.id())).isNull();

        // The case the design is shaped around: a perfectly live fetch token presented as a bearer
        // credential on the machine API chain. The lookup resolves its row, so a stamp written
        // inside the lookup would mark this refusal as a use.
        TokenService.IssuedToken fetchOnly = tokenService.create("alice", "fetch-only-last-used");
        mockMvc.perform(bearer(get("/api/marketplaces"), fetchOnly.token())).andExpect(status().isUnauthorized());
        assertThat(lastUsed(fetchOnly.id()))
                .as("a credential the machine chain refused was not used on it")
                .isNull();

        // Positive control: the same route, the same chain, a credential that belongs on it. Without
        // this the assertions above would also hold with the recording deleted entirely.
        TokenService.IssuedToken accepted = machineCredential();
        mockMvc.perform(bearer(get("/api/marketplaces"), accepted.token())).andExpect(status().isOk());
        assertThat(lastUsed(accepted.id())).isNotNull();
    }
}

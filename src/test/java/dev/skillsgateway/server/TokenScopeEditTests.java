package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.auth.MachineApiRegistry;
import dev.skillsgateway.server.auth.TokenService;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.AccessToken;
import dev.skillsgateway.server.persistence.TokenRepository;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A live token's scopes can be edited (GW_AUTH_0083 — The owner of a personal access token can
 * change the marketplaces it fetches while it is live; GW_AUTH_0084 — An administrator can change
 * the API scopes of a live system access token), and an edit changes nothing else (GW_AUTH_0085 —
 * Changing a token's scopes changes nothing else about it), is on the ledger (GW_AUTH_0086 —
 * Every change of a token's scopes is recorded on the ledger with before and after) and accepts
 * nothing but the list (GW_AUTH_0087 — The scope-change routes accept the scope list and nothing
 * else).
 *
 * <p>Tier 3 of the old-coder discipline: the adversarial cases are first-class here, not an
 * afterthought. "owner" is the administrator named for the family in {@link
 * AbstractNamedAdminsTest}; every other principal below holds no role at all.
 */
class TokenScopeEditTests extends AbstractNamedAdminsTest {

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private SkillsGatewayProperties properties;

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().idToken(token -> token.subject("owner"));
    }

    private static OidcLoginRequestPostProcessor person(String principal) {
        return oidcLogin().idToken(token -> token.subject(principal));
    }

    private static Instant soon() {
        return Instant.now().plus(30, ChronoUnit.DAYS);
    }

    /** A registered, approved marketplace: a valid scope, and one the facade really serves. */
    private String served() throws Exception {
        String name = uniqueName("mk");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());
        return name;
    }

    private MvcResult patchToken(long id, OidcLoginRequestPostProcessor caller, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/tokens/{id}", id)
                        .with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult patchMachine(long id, OidcLoginRequestPostProcessor caller, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/tokens/machine/{id}", id)
                        .with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static String scopesBody(String... names) {
        return "{\"scopes\": [%s]}".formatted(quoted(names));
    }

    private static String apiScopesBody(String... names) {
        return "{\"apiScopes\": [%s]}".formatted(quoted(names));
    }

    private static String quoted(String... names) {
        return String.join(
                ", ", java.util.Arrays.stream(names).map(n -> "\"" + n + "\"").toList());
    }

    private AccessToken row(long id) {
        return tokenRepository.findById(id).orElseThrow();
    }

    private boolean fetches(String marketplace, String secret) throws Exception {
        return git(null, "ls-remote", facadeUrl(marketplace, secret)).exitCode() == 0;
    }

    private TokenService.IssuedToken machine(String principal, String... scopes) {
        return tokenService.createMachineCredential(principal, "pipeline", List.of(scopes), soon(), "root");
    }

    // ---- GW_AUTH_0083: the owner edits a personal token --------------------------------------

    @Test
    @SVCs({"SVC_GW_AUTH_0102"})
    void a_personal_token_can_be_narrowed_widened_and_reset_and_the_facade_follows_at_once() throws Exception {
        String a = served();
        String b = served();
        String c = served();
        String owner = uniqueName("owner");
        TokenService.IssuedToken token = tokenService.create(owner, "ci", List.of(a, b), null);
        assertThat(fetches(a, token.token())).isTrue();
        assertThat(fetches(b, token.token())).isTrue();
        assertThat(fetches(c, token.token())).as("not named yet").isFalse();

        // Narrowing: the very next request for a marketplace no longer named is refused.
        MvcResult narrowed = patchToken(token.id(), person(owner), scopesBody(a));
        assertThat(narrowed.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(narrowed.getResponse().getContentAsString(), "$.scopes"))
                .containsExactly(a);
        assertThat(fetches(a, token.token())).isTrue();
        assertThat(fetches(b, token.token())).as("narrowed away").isFalse();

        // Widening is validated as at creation and takes effect at once.
        assertThat(patchToken(token.id(), person(owner), scopesBody(a, c))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(fetches(c, token.token())).isTrue();

        // The catalog's name is a valid scope, exactly as at creation.
        String catalogName = properties.catalog().name();
        MvcResult catalog = patchToken(token.id(), person(owner), scopesBody(catalogName));
        assertThat(catalog.getResponse().getStatus()).isEqualTo(200);
        assertThat(row(token.id()).scopeList()).containsExactly(catalogName);

        // An empty list means every marketplace, as at creation.
        MvcResult everything = patchToken(token.id(), person(owner), scopesBody());
        assertThat(everything.getResponse().getStatus()).isEqualTo(200);
        assertThat(row(token.id()).scopes()).isNull();
        assertThat(fetches(b, token.token())).isTrue();

        // A name that is no marketplace is refused as at creation, and the token is as it was.
        MvcResult unknown = patchToken(token.id(), person(owner), scopesBody(a, "no-such-marketplace"));
        assertThat(unknown.getResponse().getStatus()).isEqualTo(422);
        assertThat(unknown.getResponse().getContentAsString()).contains("unknown scope 'no-such-marketplace'");
        assertThat(row(token.id()).scopes()).isNull();

        // The listing the portal reads shows the stored scopes.
        patchToken(token.id(), person(owner), scopesBody(b));
        String listed = mockMvc.perform(get("/api/v1/tokens").with(person(owner)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<String>>read(listed, "$[?(@.id == %d)].scopes[*]".formatted(token.id())))
                .containsExactly(b);
    }

    // ---- GW_AUTH_0083, GW_AUTH_0084: who and what is refused ---------------------------------

    @Test
    @SVCs({"SVC_GW_AUTH_0103"})
    void the_edit_routes_refuse_the_wrong_caller_and_the_wrong_target_and_change_nothing() throws Exception {
        String a = served();
        String owner = uniqueName("owner");
        String other = uniqueName("other");
        TokenService.IssuedToken mine = tokenService.create(owner, "mine", List.of(a), null);

        // Another user's token: indistinguishable from one that does not exist.
        assertThat(patchToken(mine.id(), person(other), scopesBody())
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(patchToken(999_999_999L, person(owner), scopesBody(a))
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(row(mine.id()).scopeList()).containsExactly(a);

        // A machine credential's id on the personal route, even from a login named like its principal.
        String shared = uniqueName("pipeline");
        TokenService.IssuedToken cred = machine(shared, "estate:read");
        assertThat(patchToken(cred.id(), person(shared), scopesBody(a))
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(patchToken(cred.id(), admin(), scopesBody(a)).getResponse().getStatus())
                .as("not even an administrator reaches a machine credential through the personal route")
                .isEqualTo(404);
        AccessToken credRow = row(cred.id());
        assertThat(credRow.scopes()).as("fetch scope stays unrelated").isNull();
        assertThat(credRow.apiScopeList()).containsExactly("estate:read");

        // A revoked token and an expired one are not live: 409, unchanged.
        TokenService.IssuedToken revoked = tokenService.create(owner, "revoked", List.of(a), null);
        tokenService.revoke(revoked.id(), owner);
        assertThat(patchToken(revoked.id(), person(owner), scopesBody())
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);
        assertThat(row(revoked.id()).scopeList()).containsExactly(a);
        AccessToken expired = tokenRepository.create(
                owner,
                "expired",
                "hash-" + uniqueName("e"),
                List.of(a),
                Instant.now().minusSeconds(60),
                null);
        assertThat(patchToken(expired.id(), person(owner), scopesBody())
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);
        assertThat(row(expired.id()).scopeList()).containsExactly(a);

        // The machine route: a session without the administrator role, then the wrong targets.
        assertThat(patchMachine(cred.id(), person(other), apiScopesBody("estate:read", "audit:read"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(patchMachine(mine.id(), admin(), apiScopesBody("estate:read"))
                        .getResponse()
                        .getStatus())
                .as("a personal token is not a machine credential")
                .isEqualTo(404);
        assertThat(patchMachine(999_999_999L, admin(), apiScopesBody("estate:read"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(row(mine.id()).apiScopes()).isNull();
        TokenService.IssuedToken deadCred = machine(uniqueName("dead"), "estate:read");
        tokenService.revokeMachineCredential(deadCred.id());
        assertThat(patchMachine(deadCred.id(), admin(), apiScopesBody("audit:read"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);
        AccessToken expiredCred = tokenRepository.create(
                uniqueName("old"),
                "expired",
                "hash-" + uniqueName("m"),
                null,
                Instant.now().minusSeconds(60),
                null,
                null,
                false,
                List.of("estate:read"),
                "root");
        assertThat(patchMachine(expiredCred.id(), admin(), apiScopesBody("audit:read"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);
        assertThat(row(expiredCred.id()).apiScopeList()).containsExactly("estate:read");
        assertThat(row(cred.id()).apiScopeList()).containsExactly("estate:read");

        // No credential reaches either route: a machine credential holding every scope, and a
        // session-derived credential, are refused and nothing changes.
        TokenService.IssuedToken everyScope =
                machine(uniqueName("allscopes"), MachineApiRegistry.scopes().toArray(String[]::new));
        TokenService.IssuedToken sessionDerived = tokenService.createSessionCredential(owner, "session", List.of(a));
        for (String secret : List.of(everyScope.token(), sessionDerived.token())) {
            int personalStatus = mockMvc.perform(patch("/api/v1/tokens/{id}", mine.id())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(scopesBody()))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            int machineStatus = mockMvc.perform(patch("/api/v1/tokens/machine/{id}", cred.id())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(apiScopesBody("audit:read")))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(personalStatus).isBetween(400, 499);
            assertThat(machineStatus).isBetween(400, 499);
        }
        assertThat(row(mine.id()).scopeList()).containsExactly(a);
        assertThat(row(cred.id()).apiScopeList()).containsExactly("estate:read");
    }

    // ---- GW_AUTH_0084: the machine edit and its invariants -----------------------------------

    @Test
    @SVCs({"SVC_GW_AUTH_0104"})
    void a_system_access_tokens_api_scopes_are_replaced_under_every_invariant_of_the_machine_model() throws Exception {
        TokenService.IssuedToken cred = machine(uniqueName("pipe"), "estate:read", "audit:read");
        int before = mockMvc.perform(
                        get("/api/v1/marketplaces").header(HttpHeaders.AUTHORIZATION, "Bearer " + cred.token()))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(before).as("not yet scoped for it").isEqualTo(403);

        MvcResult widened = patchMachine(cred.id(), admin(), apiScopesBody("estate:read", "marketplaces:read"));
        assertThat(widened.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(widened.getResponse().getContentAsString(), "$.apiScopes"))
                .containsExactly("estate:read", "marketplaces:read");
        assertThat(widened.getResponse().getContentAsString()).doesNotContain("sgw_");
        // It authenticates with exactly the new scopes, on its next request, with the same secret.
        mockMvc.perform(get("/api/v1/marketplaces").header(HttpHeaders.AUTHORIZATION, "Bearer " + cred.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, "Bearer " + cred.token()))
                .andExpect(status().isForbidden());

        MvcResult narrowed = patchMachine(cred.id(), admin(), apiScopesBody("estate:read"));
        assertThat(narrowed.getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(get("/api/v1/marketplaces").header(HttpHeaders.AUTHORIZATION, "Bearer " + cred.token()))
                .andExpect(status().isForbidden());

        // Every refusal leaves the credential as it was.
        for (String body : List.of(
                apiScopesBody(),
                apiScopesBody("estate:read", "marketplaces:regsiter"),
                apiScopesBody("*"),
                apiScopesBody("all"),
                apiScopesBody("admin"))) {
            assertThat(patchMachine(cred.id(), admin(), body).getResponse().getStatus())
                    .as(body)
                    .isEqualTo(422);
            assertThat(row(cred.id()).apiScopeList()).as(body).containsExactly("estate:read");
        }
        // A machine credential keeps an unrelated, empty fetch scope and reaches no marketplace.
        assertThat(row(cred.id()).scopes()).isNull();
        assertThat(row(cred.id()).permitsMarketplace("anything")).isFalse();
    }

    // ---- GW_AUTH_0085: nothing else moves, rotation carries, and the race ---------------------

    @Test
    @SVCs({"SVC_GW_AUTH_0105"})
    void an_edit_touches_nothing_else_and_a_rotation_carries_the_edited_scopes() throws Exception {
        String a = served();
        String b = served();
        String owner = uniqueName("owner");

        // A personal token with a push grant and an expiry, and a session-derived one.
        AccessToken personal = tokenRepository.create(
                owner,
                "ci",
                "hash-" + uniqueName("p"),
                List.of(a, b),
                soon().truncatedTo(ChronoUnit.MILLIS),
                null,
                List.of("hosted-x"));
        AccessToken beforePersonal = row(personal.id());
        assertThat(patchToken(personal.id(), person(owner), scopesBody(a))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        AccessToken afterPersonal = row(personal.id());
        assertThat(afterPersonal.scopeList()).containsExactly(a);
        assertThat(afterPersonal)
                .usingRecursiveComparison()
                .ignoringFields("scopes")
                .isEqualTo(beforePersonal);

        TokenService.IssuedToken session = tokenService.createSessionCredential(owner, "session", List.of(a, b));
        AccessToken beforeSession = row(session.id());
        assertThat(patchToken(session.id(), person(owner), scopesBody(b))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(row(session.id()))
                .usingRecursiveComparison()
                .ignoringFields("scopes")
                .isEqualTo(beforeSession);

        // Rotation after the edit carries the edited scopes and the rest of the grant.
        TokenService.IssuedToken rotated =
                tokenService.rotate(personal.id(), owner).orElseThrow();
        assertThat(rotated.scopes()).containsExactly(a);
        assertThat(rotated.pushScopes()).containsExactly("hosted-x");
        assertThat(rotated.expiresAt()).isEqualTo(beforePersonal.expiresAt());
        // The predecessor is revoked by the rotation, so an edit of it is refused.
        assertThat(patchToken(personal.id(), person(owner), scopesBody(b))
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);
        assertThat(row(rotated.id()).scopeList()).containsExactly(a);

        // The same for a machine credential: only apiScopes move, rotation carries them.
        TokenService.IssuedToken cred = machine(uniqueName("pipe"), "estate:read");
        AccessToken beforeCred = row(cred.id());
        assertThat(patchMachine(cred.id(), admin(), apiScopesBody("audit:read", "estate:read"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(row(cred.id()))
                .usingRecursiveComparison()
                .ignoringFields("apiScopes")
                .isEqualTo(beforeCred);
        TokenService.IssuedToken rotatedCred =
                tokenService.rotateMachineCredential(cred.id()).orElseThrow();
        assertThat(rotatedCred.apiScopes()).containsExactly("audit:read", "estate:read");
        assertThat(patchMachine(cred.id(), admin(), apiScopesBody("estate:read"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);
    }

    /**
     * An edit and a rotation of one token, started together, many times. Whatever the interleaving,
     * an edit reported saved is in the successor the rotation issued; an edit that lost the race is
     * refused (409) rather than applied to a dead token. Against a rotation that copies the row it
     * read before revoking, an edit committed in between is reported saved and lost.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0105"})
    void an_edit_racing_a_rotation_is_never_lost_from_the_successor() throws Exception {
        String a = served();
        String b = served();
        String owner = uniqueName("owner");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int bothSucceeded = 0;
        try {
            for (int i = 0; i < 400; i++) {
                TokenService.IssuedToken token = tokenService.create(owner, "race-" + i, List.of(a), null);
                CountDownLatch go = new CountDownLatch(1);
                Callable<MvcResult> edit = () -> {
                    go.await();
                    return patchToken(token.id(), person(owner), scopesBody(b));
                };
                Callable<MvcResult> rotate = () -> {
                    go.await();
                    return mockMvc.perform(post("/api/v1/tokens/{id}/rotate", token.id())
                                    .with(person(owner)))
                            .andReturn();
                };
                Future<MvcResult> editing = pool.submit(edit);
                Future<MvcResult> rotating = pool.submit(rotate);
                go.countDown();
                MvcResult edited = editing.get();
                MvcResult rotated = rotating.get();
                assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
                assertThat(edited.getResponse().getStatus()).isIn(200, 409);
                if (edited.getResponse().getStatus() == 200) {
                    bothSucceeded++;
                    long successor = JsonPath.<Number>read(rotated.getResponse().getContentAsString(), "$.id")
                            .longValue();
                    assertThat(row(successor).scopeList())
                            .as("iteration %d: an edit reported saved must be in the successor", i)
                            .containsExactly(b);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(bothSucceeded)
                .as("the race produced edits that won, or the test proves nothing")
                .isPositive();
    }

    // ---- GW_AUTH_0086: the ledger --------------------------------------------------------------

    private List<Map<String, Object>> entries(String event, long tokenId) {
        String needle = (event.startsWith("machine") ? "credential " : "token ") + tokenId + " '";
        return fetchLogRepository.list().stream()
                .filter(entry -> event.equals(entry.get("event")))
                .filter(entry -> String.valueOf(entry.get("detail")).startsWith(needle))
                .toList();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0106"})
    void a_scope_change_writes_one_entry_with_actor_before_and_after_and_a_refusal_writes_none() throws Exception {
        String a = served();
        String b = served();
        String owner = uniqueName("owner");
        TokenService.IssuedToken token = tokenService.create(owner, "ci", List.of(a, b), null);

        patchToken(token.id(), person(owner), scopesBody(a));
        patchToken(token.id(), person(owner), scopesBody());
        List<Map<String, Object>> changes = entries("token-scopes-changed", token.id());
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).get("principal")).isEqualTo(owner);
        assertThat(changes.get(0).get("detail"))
                .isEqualTo("token %d 'ci' scopes [%s, %s] -> [%s]"
                        .formatted(token.id(), a.compareTo(b) < 0 ? a : b, a.compareTo(b) < 0 ? b : a, a));
        assertThat(changes.get(1).get("detail")).isEqualTo("token %d 'ci' scopes [%s] -> all".formatted(token.id(), a));

        // Refusals write nothing: unknown scope, not live, not the caller's, strict-body.
        patchToken(token.id(), person(owner), scopesBody("no-such-marketplace"));
        patchToken(token.id(), person(uniqueName("other")), scopesBody(a));
        patchToken(token.id(), person(owner), "{\"scopes\": [], \"pushScopes\": [\"x\"]}");
        assertThat(entries("token-scopes-changed", token.id())).hasSize(2);

        String pipeline = uniqueName("pipe");
        TokenService.IssuedToken cred = machine(pipeline, "estate:read");
        patchMachine(cred.id(), admin(), apiScopesBody("estate:read", "audit:read"));
        List<Map<String, Object>> machineChanges = entries("machine-credential-scopes-changed", cred.id());
        assertThat(machineChanges).hasSize(1);
        assertThat(machineChanges.get(0).get("principal")).isEqualTo("owner");
        assertThat(machineChanges.get(0).get("detail"))
                .isEqualTo("credential %d 'pipeline' principal=%s scopes [estate:read] -> [audit:read, estate:read]"
                        .formatted(cred.id(), pipeline));
        patchMachine(cred.id(), admin(), apiScopesBody("*"));
        patchMachine(cred.id(), person(uniqueName("other")), apiScopesBody("audit:read"));
        assertThat(entries("machine-credential-scopes-changed", cred.id())).hasSize(1);

        // Two edits at once form a chain: one's "after" is the other's "before".
        TokenService.IssuedToken contested = tokenService.create(owner, "contested", List.of(a), null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<MvcResult> first = pool.submit(() -> {
                go.await();
                return patchToken(contested.id(), person(owner), scopesBody(b));
            });
            Future<MvcResult> second = pool.submit(() -> {
                go.await();
                return patchToken(contested.id(), person(owner), scopesBody());
            });
            go.countDown();
            assertThat(first.get().getResponse().getStatus()).isEqualTo(200);
            assertThat(second.get().getResponse().getStatus()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        List<Map<String, Object>> chain = entries("token-scopes-changed", contested.id());
        assertThat(chain).hasSize(2);
        List<String> details = new ArrayList<>(
                chain.stream().map(entry -> String.valueOf(entry.get("detail"))).toList());
        String prefix = "token %d 'contested' scopes ".formatted(contested.id());
        String firstChange = details.get(0).substring(prefix.length());
        String secondChange = details.get(1).substring(prefix.length());
        assertThat(firstChange.split(" -> ")[0])
                .as("the first change starts from the original")
                .isEqualTo("[" + a + "]");
        assertThat(secondChange.split(" -> ")[0])
                .as("the second starts where the first ended")
                .isEqualTo(firstChange.split(" -> ")[1]);
    }

    // ---- GW_AUTH_0087: strict bodies -----------------------------------------------------------

    @Test
    @SVCs({"SVC_GW_AUTH_0107"})
    void a_request_with_any_other_field_or_without_the_list_is_refused_and_changes_nothing() throws Exception {
        String a = served();
        String owner = uniqueName("owner");
        TokenService.IssuedToken token = tokenService.create(owner, "ci", List.of(a), null);
        List<String> extras = List.of(
                "{\"scopes\": [\"%s\"], \"pushScopes\": [\"hosted-x\"]}".formatted(a),
                "{\"pushScopes\": [\"hosted-x\"]}",
                "{\"scopes\": [\"%s\"], \"apiScopes\": [\"estate:read\"]}".formatted(a),
                "{\"apiScopes\": [\"estate:read\"]}",
                "{\"scopes\": [\"%s\"], \"expiresAt\": \"2099-01-01T00:00:00Z\"}".formatted(a),
                "{\"scopes\": [\"%s\"], \"name\": \"renamed\"}".formatted(a));
        for (String body : extras) {
            assertThat(patchToken(token.id(), person(owner), body).getResponse().getStatus())
                    .as(body)
                    .isEqualTo(400);
        }
        for (String body : List.of("{}", "{\"scopes\": null}")) {
            assertThat(patchToken(token.id(), person(owner), body).getResponse().getStatus())
                    .as(body)
                    .isEqualTo(422);
        }
        AccessToken afterPersonal = row(token.id());
        assertThat(afterPersonal.scopeList()).containsExactly(a);
        assertThat(afterPersonal.pushScopes()).isNull();
        assertThat(afterPersonal.apiScopes()).isNull();
        assertThat(afterPersonal.name()).isEqualTo("ci");
        assertThat(afterPersonal.expiresAt()).isNull();

        String pipeline = uniqueName("pipe");
        TokenService.IssuedToken cred = machine(pipeline, "estate:read");
        Instant expires = row(cred.id()).expiresAt();
        for (String body : List.of(
                "{\"apiScopes\": [\"audit:read\"], \"scopes\": [\"%s\"]}".formatted(a),
                "{\"scopes\": [\"%s\"]}".formatted(a),
                "{\"apiScopes\": [\"audit:read\"], \"expiresAt\": \"2099-01-01T00:00:00Z\"}",
                "{\"apiScopes\": [\"audit:read\"], \"neverExpires\": true}",
                "{\"apiScopes\": [\"audit:read\"], \"pushScopes\": [\"hosted-x\"]}",
                "{\"apiScopes\": [\"audit:read\"], \"principal\": \"someone-else\"}")) {
            assertThat(patchMachine(cred.id(), admin(), body).getResponse().getStatus())
                    .as(body)
                    .isEqualTo(400);
        }
        for (String body : List.of("{}", "{\"apiScopes\": null}")) {
            assertThat(patchMachine(cred.id(), admin(), body).getResponse().getStatus())
                    .as(body)
                    .isEqualTo(422);
        }
        AccessToken afterCred = row(cred.id());
        assertThat(afterCred.apiScopeList()).containsExactly("estate:read");
        assertThat(afterCred.scopes()).isNull();
        assertThat(afterCred.pushScopes()).isNull();
        assertThat(afterCred.expiresAt()).isEqualTo(expires);
        assertThat(afterCred.principal()).isEqualTo(pipeline);
    }
}

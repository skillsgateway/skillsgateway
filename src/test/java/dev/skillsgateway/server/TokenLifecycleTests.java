package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.auth.TokenService;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Token scopes, expiry, rotation and attribution (GW_0064–GW_0067). The facade is the trust
 * boundary these land on, so every access decision is verified with a real git client against the
 * wire, not a repository field.
 */
class TokenLifecycleTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0064"})
    void scoped_tokens_fetch_only_their_marketplaces_and_cannot_probe_the_rest() throws Exception {
        Registered inScope = registerAndIngest(uniqueName("scin"), createUpstream(DEFAULT_MANIFEST));
        Registered outOfScope = registerAndIngest(uniqueName("scout"), createUpstream(DEFAULT_MANIFEST));
        approve(inScope.snapshot().id());
        approve(outOfScope.snapshot().id());
        String inScopeName = inScope.marketplace().name();
        String outName = outOfScope.marketplace().name();

        String scoped = tokenService
                .create("alice", "scoped", List.of(inScopeName, "catalog"), null)
                .token();

        // In scope: the marketplace and the catalog both clone.
        assertThat(gitClone(facadeUrl(inScopeName, scoped), newWorkDir("scin")).exitCode())
                .isZero();
        assertThat(gitClone(facadeUrl("catalog", scoped), newWorkDir("sccat")).exitCode())
                .isZero();

        // Out of scope answers exactly like nonexistent: same exit code, same wire-visible error.
        String ghostName = uniqueName("ghost");
        GitResult outOfScopeResult = gitClone(facadeUrl(outName, scoped), newWorkDir("scout"));
        GitResult nonexistentResult = gitClone(facadeUrl(ghostName, scoped), newWorkDir("scghost"));
        assertThat(outOfScopeResult.exitCode()).isEqualTo(nonexistentResult.exitCode());
        assertThat(outOfScopeResult.exitCode()).isNotZero();
        assertThat(fatalLines(outOfScopeResult.output(), outName))
                .isNotEmpty()
                .isEqualTo(fatalLines(nonexistentResult.output(), ghostName));

        // An unscoped token still fetches everything.
        assertThat(gitClone(facadeUrl(outName, newPat()), newWorkDir("scall")).exitCode())
                .isZero();

        // A scope that names nothing registered is refused at creation.
        assertThatThrownBy(() -> tokenService.create("alice", "typo", List.of(uniqueName("nope")), null))
                .isInstanceOf(TokenService.InvalidTokenRequestException.class);
    }

    /**
     * The wire-visible error, masked of run-specific values (name, credentials): what must be
     * byte-identical between an out-of-scope answer and a nonexistent-marketplace answer.
     */
    private static String fatalLines(String output, String marketplace) {
        return output.lines()
                .filter(line -> line.startsWith("fatal:") || line.startsWith("remote:"))
                .map(line -> line.replace(marketplace, "<name>").replaceAll("token:[^@]+@", "token:<pat>@"))
                .reduce("", (left, right) -> left + right + "\n");
    }

    @Test
    @SVCs({"SVC_GW_0065"})
    void an_expired_token_is_refused_at_authentication_with_no_sweep_involved() throws Exception {
        Registered registered = registerAndIngest(uniqueName("exp"), createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());
        String name = registered.marketplace().name();

        String live = tokenService
                .create("alice", "living", List.of(), Instant.now().plus(Duration.ofHours(1)))
                .token();
        assertThat(gitClone(facadeUrl(name, live), newWorkDir("explive")).exitCode())
                .isZero();

        // Already past its deadline the moment it is presented: refusal is a comparison at
        // authentication time — nothing scheduled has run.
        String dead = tokenService
                .create("alice", "dead", List.of(), Instant.now().minus(Duration.ofSeconds(1)))
                .token();
        GitResult refused = gitClone(facadeUrl(name, dead), newWorkDir("expdead"));
        assertThat(refused.exitCode()).isNotZero();
        assertThat(refused.output()).contains("Authentication failed");
    }

    @Test
    @SVCs({"SVC_GW_0066"})
    void rotation_changes_the_secret_and_nothing_else() throws Exception {
        Registered registered = registerAndIngest(uniqueName("rot"), createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());
        String name = registered.marketplace().name();
        // Microsecond precision: TIMESTAMPTZ stores micros, and the round-trip equality below
        // must not depend on the platform clock's resolution (nanos on Linux, micros on macOS).
        Instant deadline = Instant.now().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.MICROS);

        TokenService.IssuedToken original = tokenService.create("alice", "rotme", List.of(name), deadline);
        assertThat(gitClone(facadeUrl(name, original.token()), newWorkDir("rota"))
                        .exitCode())
                .isZero();

        TokenService.IssuedToken rotated =
                tokenService.rotate(original.id(), "alice").orElseThrow();

        // Identical grant: name, scopes, the same deadline, and the lineage recorded.
        assertThat(rotated.name()).isEqualTo("rotme");
        assertThat(rotated.scopes()).containsExactly(name);
        assertThat(rotated.expiresAt()).isEqualTo(deadline);
        assertThat(rotated.rotatedFrom()).isEqualTo(original.id());

        // New secret works; the old one is dead immediately.
        assertThat(gitClone(facadeUrl(name, rotated.token()), newWorkDir("rotb"))
                        .exitCode())
                .isZero();
        GitResult old = gitClone(facadeUrl(name, original.token()), newWorkDir("rotc"));
        assertThat(old.exitCode()).isNotZero();
        assertThat(old.output()).contains("Authentication failed");

        // Nobody else's, and nothing dead, can be rotated.
        assertThat(tokenService.rotate(rotated.id(), "mallory")).isEmpty();
        assertThatThrownBy(() -> tokenService.rotate(original.id(), "alice"))
                .isInstanceOf(TokenService.TokenNotRotatableException.class);
    }

    @Test
    @SVCs({"SVC_GW_0067"})
    void every_fetch_names_its_token_and_the_token_lifecycle_is_on_the_ledger() throws Exception {
        Registered registered = registerAndIngest(uniqueName("attr"), createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());
        String name = registered.marketplace().name();

        String me = JsonPath.read(
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/me")
                                .with(oidcLogin()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.username");

        String created = mockMvc.perform(post("/api/tokens")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"laptop\",\"scopes\":[\"%s\"]}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long firstId = ((Number) JsonPath.read(created, "$.id")).longValue();
        String firstSecret = JsonPath.read(created, "$.token");
        TokenService.IssuedToken second = tokenService.create(me, "ci", List.of(), null);

        assertThat(gitClone(facadeUrl(name, firstSecret), newWorkDir("attra")).exitCode())
                .isZero();
        assertThat(gitClone(facadeUrl(name, second.token()), newWorkDir("attrb"))
                        .exitCode())
                .isZero();

        // Each facade entry carries the id of the token that authenticated it.
        List<Map<String, Object>> fetches = fetchLogRepository.list().stream()
                .filter(entry -> name.equals(entry.get("marketplace")) && "upload-pack".equals(entry.get("event")))
                .toList();
        assertThat(fetches)
                .extracting(entry -> ((Number) entry.get("token_id")).longValue())
                .contains(firstId, second.id());

        // The export rows carry the same attribution.
        List<FetchLogRepository.AuditEntry> exported =
                fetchLogRepository.entriesAfter(0, Instant.now().plus(Duration.ofMinutes(1)), Integer.MAX_VALUE);
        assertThat(exported)
                .filteredOn(entry -> name.equals(entry.marketplace()) && "upload-pack".equals(entry.event()))
                .extracting(FetchLogRepository.AuditEntry::tokenId)
                .contains(firstId, second.id());

        // The lifecycle is on the ledger with identity, name and scopes.
        mockMvc.perform(post("/api/tokens/%d/rotate".formatted(firstId)).with(oidcLogin()))
                .andExpect(status().isOk());
        assertThat(fetchLogRepository.list())
                .anySatisfy(entry -> {
                    assertThat(entry.get("event")).isEqualTo("token-created");
                    assertThat(entry.get("principal")).isEqualTo(me);
                    assertThat((String) entry.get("detail"))
                            .contains("'laptop'")
                            .contains(name);
                })
                .anySatisfy(entry -> {
                    assertThat(entry.get("event")).isEqualTo("token-rotated");
                    assertThat((String) entry.get("detail")).contains("'laptop'");
                });
    }
}

package io.github.jimisola.skillsgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.jimisola.skillsgateway.auth.TokenService;
import io.github.jimisola.skillsgateway.persistence.AccessToken;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AuthTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0011"})
    void webAccessRequiresOidcSession() throws Exception {
        // A browser sends Accept: text/html; that is what routes to the login redirect.
        mockMvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oauth2/authorization/idp"));

        mockMvc.perform(get("/api/marketplaces")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/marketplaces").with(oidcLogin())).andExpect(status().isOk());
    }

    @Test
    @SVCs({"SVC_GW_0012"})
    void gitFacadeRequiresValidPersonalAccessToken() throws Exception {
        String name = uniqueName("corp");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());

        GitResult anonymous = gitClone(facadeUrl(name, null), newWorkDir("anon"));
        assertThat(anonymous.exitCode()).as(anonymous.output()).isNotZero();

        GitResult invalid = gitClone(facadeUrl(name, "sgw_definitely-not-a-token"), newWorkDir("invalid"));
        assertThat(invalid.exitCode()).as(invalid.output()).isNotZero();

        TokenService.IssuedToken revokable = tokenService.create("alice", "to-revoke");
        assertThat(tokenService.revoke(revokable.id(), "alice")).isTrue();
        GitResult revoked = gitClone(facadeUrl(name, revokable.token()), newWorkDir("revoked"));
        assertThat(revoked.exitCode()).as(revoked.output()).isNotZero();

        GitResult valid = gitClone(facadeUrl(name, newPat()), newWorkDir("valid"));
        assertThat(valid.exitCode()).as(valid.output()).isZero();

        List<Map<String, Object>> uploadPacks = fetchLogRepository.list().stream()
                .filter(row -> name.equals(row.get("marketplace")))
                .filter(row -> "upload-pack".equals(row.get("event")))
                .toList();
        assertThat(uploadPacks).isNotEmpty();
        assertThat(uploadPacks).extracting(row -> row.get("principal")).containsOnly("alice");
    }

    @Test
    @SVCs({"SVC_GW_0013"})
    void tokensAreShownOnceStoredHashedAndRevocable() throws Exception {
        String name = uniqueName("corp");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());

        String createBody = mockMvc.perform(post("/api/tokens")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ci\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = JsonPath.read(createBody, "$.token");
        long id = ((Number) JsonPath.read(createBody, "$.id")).longValue();
        assertThat(token).startsWith("sgw_");
        assertThat(createBody.split(Pattern.quote(token), -1))
                .as("cleartext appears exactly once")
                .hasSize(2);

        String listBody = mockMvc.perform(get("/api/tokens").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listBody).doesNotContain(token);
        List<Map<String, Object>> entries = JsonPath.read(listBody, "$[?(@.id == " + id + ")]");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).keySet()).containsExactlyInAnyOrder("id", "name", "createdAt", "revokedAt");

        AccessToken stored = tokenService.authenticate(token).orElseThrow();
        assertThat(stored.tokenHash()).isNotEqualTo(token);

        mockMvc.perform(delete("/api/tokens/" + id).with(oidcLogin())).andExpect(status().isNoContent());

        assertThat(tokenService.authenticate(token)).isEmpty();
        GitResult afterRevocation = gitClone(facadeUrl(name, token), newWorkDir("revoked"));
        assertThat(afterRevocation.exitCode()).as(afterRevocation.output()).isNotZero();
    }
}

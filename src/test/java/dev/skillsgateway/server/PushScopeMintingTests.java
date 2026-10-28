package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.roles.RoleGrant;
import dev.skillsgateway.server.roles.RoleService;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Who may be given a push scope (GW_FACADE_0007): an administrator or an approver of every marketplace
 * the request names. Each caller is a unique subject, because the shared context's
 * {@code user,root,alice} are administrators and the default {@code oidcLogin()} subject is
 * {@code user}.
 */
class PushScopeMintingTests extends AbstractGatewayTest {

    @Autowired
    private GitStorage storage;

    @Autowired
    private MarketplaceRepository marketplaceRepository;

    @Autowired
    private RoleService roleService;

    private String registerHosted(String prefix) {
        String name = uniqueName(prefix);
        marketplaceRepository.register(name, null, null, Marketplace.ORIGIN_HOSTED, Marketplace.PUSH_APPEND_ONLY, null);
        try (Repository ignored = storage.hosted(name)) {
            return name;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OidcLoginRequestPostProcessor session(String subject) {
        return oidcLogin().idToken(token -> token.subject(subject));
    }

    private ResultActions mint(OidcLoginRequestPostProcessor caller, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/tokens")
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String pushRequest(String... marketplaces) {
        return "{\"name\":\"publisher\",\"pushScopes\":[%s]}"
                .formatted(String.join(
                        ",",
                        List.of(marketplaces).stream()
                                .map(name -> "\"" + name + "\"")
                                .toList()));
    }

    private void assertNothingIssuedTo(String principal) {
        assertThat(tokenService.list(principal))
                .as("tokens held by " + principal)
                .isEmpty();
        assertThat(fetchLogRepository.list()).noneSatisfy(entry -> {
            assertThat(entry.get("event")).isEqualTo("token-created");
            assertThat(entry.get("principal")).isEqualTo(principal);
        });
    }

    @Test
    @SVCs({"SVC_GW_FACADE_0035"})
    void a_session_holding_no_role_is_refused_a_push_scope_and_holds_no_token() throws Exception {
        String hosted = registerHosted("mintnorole");
        String nobody = uniqueName("norole");

        mint(session(nobody), pushRequest(hosted)).andExpect(status().isForbidden());

        assertNothingIssuedTo(nobody);
    }

    @Test
    @SVCs({"SVC_GW_FACADE_0035"})
    void an_approver_is_given_a_push_scope_for_their_marketplace_and_it_publishes() throws Exception {
        String hosted = registerHosted("mintapprover");
        String approver = uniqueName("approver");
        roleService.grant(approver, RoleGrant.APPROVER, hosted, "root");

        String created = mint(session(approver), pushRequest(hosted))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pushScopes[0]").value(hosted))
                .andExpect(jsonPath("$.pushScopes.length()").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String secret = JsonPath.read(created, "$.token");

        GitResult push = git(publisherWorkingCopy(), "push", publishUrl(hosted, secret), "main");
        assertThat(push.exitCode()).as(push.output()).isZero();
        try (Repository origin = storage.hosted(hosted)) {
            assertThat(origin.resolve(Marketplace.LINEAGE_REF)).isNotNull();
        }
    }

    @Test
    @SVCs({"SVC_GW_FACADE_0035"})
    void an_approver_naming_a_marketplace_they_do_not_approve_is_refused_the_whole_request() throws Exception {
        String theirs = registerHosted("minttheirs");
        String other = registerHosted("mintother");
        String approver = uniqueName("approver");
        roleService.grant(approver, RoleGrant.APPROVER, theirs, "root");

        // Either order: a check that looked only at the first name would pass one of these.
        mint(session(approver), pushRequest(theirs, other)).andExpect(status().isForbidden());
        mint(session(approver), pushRequest(other, theirs)).andExpect(status().isForbidden());

        assertNothingIssuedTo(approver);
    }

    @Test
    @SVCs({"SVC_GW_FACADE_0035"})
    void an_administrator_is_given_a_push_scope_for_any_hosted_marketplace() throws Exception {
        String hosted = registerHosted("mintadmin");

        // "root" is a configured administrator of the shared context, and approves nothing.
        mint(session("root"), pushRequest(hosted))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pushScopes[0]").value(hosted));
    }

    @Test
    @SVCs({"SVC_GW_FACADE_0035"})
    void a_session_holding_no_role_still_mints_a_token_without_a_push_scope() throws Exception {
        String nobody = uniqueName("norole");

        mint(session(nobody), "{\"name\":\"laptop\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pushScopes.length()").value(0));

        assertThat(tokenService.list(nobody)).hasSize(1);
    }

    /** A local repository with one commit on the lineage branch, ready to push. */
    private Path publisherWorkingCopy() throws Exception {
        Path dir = newWorkDir("minted-publisher");
        git(dir, "init", "--initial-branch=main");
        git(dir, "config", "user.email", "publisher@example.com");
        git(dir, "config", "user.name", "Publisher");
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(MANIFEST_PATH), DEFAULT_MANIFEST, StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("plugins/hello/skills/hello"));
        Files.writeString(dir.resolve("plugins/hello/skills/hello/SKILL.md"), "# Hello skill\n");
        git(dir, "add", "-A");
        git(dir, "-c", "commit.gpgsign=false", "commit", "-q", "-m", "first-party content");
        return dir;
    }
}

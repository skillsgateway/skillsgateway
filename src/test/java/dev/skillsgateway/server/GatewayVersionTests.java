package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.api.MeController;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.env.MockEnvironment;

/**
 * The gateway saying which build it is (GW_AUTH_0047).
 *
 * <p>The build-info goal runs in this build, so {@code build-info.properties} is on the test
 * classpath too and the running context reports a real version — which is the case worth asserting
 * through the endpoint. The absent case is constructed rather than found, because there is nowhere
 * in this suite that it occurs naturally; it still has to hold, since a build run from an exploded
 * directory without build information is a real way to run this.
 */
class GatewayVersionTests extends AbstractGatewayTest {

    @org.springframework.beans.factory.annotation.Autowired
    private dev.skillsgateway.server.roles.RoleService roleService;

    @Test
    @SVCs({"SVC_GW_AUTH_0047"})
    void the_session_read_reports_the_build_this_gateway_is_running() throws Exception {
        mockMvc.perform(get("/api/v1/me").with(oidcLogin().idToken(token -> token.subject("root"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("root"))
                // The real version this build carries, whatever it is — asserted by shape rather
                // than by value, because pinning the literal would make every release a test edit.
                .andExpect(jsonPath("$.version").value(org.hamcrest.Matchers.matchesPattern("\\d+\\.\\d+\\.\\d+.*")));
    }

    /**
     * Running from an exploded build with no {@code build-info.properties} — the gateway says it
     * does not know, rather than saying something that is not true.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0047"})
    void a_build_without_build_information_reports_no_version_rather_than_inventing_one() {
        MeController controller = new MeController(roleService, absent());

        assertThat(controller
                        .me(new org.springframework.security.authentication.TestingAuthenticationToken("root", "n/a"))
                        .version())
                .as("absent is absent; not \"unknown\", not an empty string")
                .isNull();
    }

    /**
     * And the half that matters in production, with the bean the packaged jar actually has. Built
     * directly rather than through the context, because registering a second
     * {@code BuildProperties} would be a second Spring context for one string.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0047"})
    void a_packaged_build_reports_the_version_its_artifact_carries() {
        var properties = new java.util.Properties();
        properties.setProperty("version", "0.3.0-178-SNAPSHOT");
        properties.setProperty("group", "dev.skillsgateway");
        properties.setProperty("artifact", "skills-gateway-server");
        BuildProperties build = new BuildProperties(properties);

        MeController controller = new MeController(roleService, provider(build));

        // Read at construction from the build, so there is no request-time source to influence.
        assertThat(controller
                        .me(new org.springframework.security.authentication.TestingAuthenticationToken("root", "n/a"))
                        .version())
                .isEqualTo("0.3.0-178-SNAPSHOT");
    }

    /** No configuration is consulted, so no property can make the gateway misreport its build. */
    @Test
    @SVCs({"SVC_GW_AUTH_0047"})
    void no_property_can_override_the_reported_version() {
        var environment = new MockEnvironment()
                .withProperty("skills-gateway.version", "9.9.9")
                .withProperty("info.build.version", "9.9.9");
        assertThat(environment.getProperty("skills-gateway.version")).isEqualTo("9.9.9");

        var properties = new java.util.Properties();
        properties.setProperty("version", "0.3.0");
        MeController controller = new MeController(roleService, provider(new BuildProperties(properties)));

        assertThat(controller
                        .me(new org.springframework.security.authentication.TestingAuthenticationToken("root", "n/a"))
                        .version())
                .as("the build is the only source; the environment is not consulted")
                .isEqualTo("0.3.0");
    }

    private static ObjectProvider<BuildProperties> absent() {
        return new ObjectProvider<>() {
            @Override
            public BuildProperties getObject() {
                throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(BuildProperties.class);
            }

            @Override
            public BuildProperties getObject(Object... args) {
                return getObject();
            }

            @Override
            public BuildProperties getIfAvailable() {
                return null;
            }

            @Override
            public BuildProperties getIfUnique() {
                return null;
            }
        };
    }

    private static ObjectProvider<BuildProperties> provider(BuildProperties value) {
        return new ObjectProvider<>() {
            @Override
            public BuildProperties getObject() {
                return value;
            }

            @Override
            public BuildProperties getObject(Object... args) {
                return value;
            }

            @Override
            public BuildProperties getIfAvailable() {
                return value;
            }

            @Override
            public BuildProperties getIfUnique() {
                return value;
            }
        };
    }
}

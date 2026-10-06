package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.skillsgateway.server.adoption.AdoptionService;
import dev.skillsgateway.server.adoption.SnapshotContentResolver;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.storage.ServedTip;
import io.github.reqstool.annotations.SVCs;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The gateway accepts no client-reported usage telemetry and reports nothing derived from it
 * (GW_OBSERVABILITY_0008, ADR 0016). These assertions are written to fail on a future change: a new
 * write surface outside the known ones, an OTLP receiver, or an adoption report that starts reading
 * from anything but the ledger and published storage.
 */
class ClientTelemetryBoundaryTests extends AbstractGatewayTest {

    /**
     * The surfaces that may accept a write. Operator API, the forge push webhook, the held-content
     * status check machines call, the sign-in session exchange, and Spring's error dispatch, which
     * maps every method. A route outside these is a failure until someone decides, here, what it is.
     */
    private static final List<String> WRITE_SURFACES =
            List.of("/api/v1/", "/hooks/", "/status/v1/", "/session", "/error");

    /**
     * The servlets that may be mounted: Spring's dispatcher, whose routes are walked above, the
     * read-only facade and the gateway-hosted publish path.
     */
    private static final Set<String> SERVLET_MAPPINGS = Set.of("/", "/git/*", "/publish/*");

    private static final List<String> OTLP_PATHS = List.of("/v1/metrics", "/v1/logs", "/v1/traces");

    @Test
    @SVCs({"SVC_GW_OBSERVABILITY_0008"})
    void no_surface_accepts_client_reported_telemetry_and_no_adoption_figure_reads_from_one() throws Exception {
        Set<String> writes = writeRoutes();
        assertThat(writes).isNotEmpty();
        assertThat(writes)
                .as("every POST/PUT/PATCH route sits on a known write surface")
                .allSatisfy(route -> assertThat(WRITE_SURFACES)
                        .anySatisfy(surface -> assertThat(route.substring(route.indexOf(' ') + 1))
                                .startsWith(surface)));
        assertThat(writes)
                .as("no route is named for telemetry intake")
                .noneMatch(route -> route.matches("(?i).*(telemetry|otlp|metrics|traces|/logs|usage|invocation).*"));

        Set<String> servlets = new TreeSet<>();
        for (ServletRegistrationBean<?> bean : webApplicationContext
                .getBeansOfType(ServletRegistrationBean.class)
                .values()) {
            servlets.addAll(bean.getUrlMappings());
        }
        assertThat(servlets).isSubsetOf(SERVLET_MAPPINGS);

        // 404 exactly: a mapped receiver that refused this payload would still be a receiver. The CSRF
        // token keeps a CSRF refusal from passing for "not mapped".
        for (String path : OTLP_PATHS) {
            int status = mockMvc.perform(post(path)
                            .with(oidcLogin().idToken(token -> token.subject("admin")))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"resourceMetrics\":[]}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).as("POST %s is not mapped", path).isEqualTo(404);
        }

        // The adoption reports read the ledger, the served tips and the published commit tree, and
        // nothing else: a dependency on any other source is a figure this test cannot vouch for.
        Constructor<?>[] constructors = AdoptionService.class.getConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(Arrays.stream(constructors[0].getParameters()).map(Parameter::getType))
                .containsExactlyInAnyOrder(FetchLogRepository.class, ServedTip.class, SnapshotContentResolver.class);
    }

    private Set<String> writeRoutes() {
        Set<RequestMethod> writes = Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH);
        Set<String> routes = new TreeSet<>();
        for (RequestMappingHandlerMapping mapping : webApplicationContext
                .getBeansOfType(RequestMappingHandlerMapping.class)
                .values()) {
            for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
                // A mapping that names no method answers every method, a write included.
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                for (RequestMethod method : methods.isEmpty() ? writes : methods) {
                    if (writes.contains(method)) {
                        for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                            routes.add(method.name() + " " + pattern);
                        }
                    }
                }
            }
        }
        return routes;
    }
}

package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.auth.MachineApiRegistry;
import dev.skillsgateway.server.auth.MachineApiRegistry.Route;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The allowlist guard. Every mapped {@code /api/**} route the running application serves must be
 * classified in {@link MachineApiRegistry} — under a named scope or on the explicit unreachable
 * list — and the registry must name no route the application does not serve.
 *
 * <p>This is what makes the allowlist an allowlist rather than a snapshot of one afternoon's
 * inventory: an endpoint added without a deliberate classification fails the build, so a new act
 * of judgement is unreachable by default instead of being admitted by silence.
 *
 * <p>The guard fails closed by construction — it compares two sets and asserts equality, so a
 * route it cannot classify is a mismatch rather than a skipped item. Its negative control (a
 * throwaway endpoint, watched failing) is recorded in the change's evidence report.
 */
class MachineApiRegistryTests extends AbstractGatewayTest {

    @Test
    void every_mapped_api_route_is_classified_and_the_registry_names_no_route_that_does_not_exist() {
        assertThat(classifiedRoutes())
                .as("MachineApiRegistry must classify exactly the /api/** routes the application serves")
                .containsExactlyInAnyOrderElementsOf(apiRoutesFromTheRouteTable());
    }

    @Test
    void no_route_is_both_reachable_and_unreachable() {
        Set<Route> reachable = new TreeSet<>();
        MachineApiRegistry.scopes().forEach(scope -> reachable.addAll(MachineApiRegistry.routesOf(scope)));
        assertThat(reachable)
                .as("a route on the unreachable list must not also be granted by a scope")
                .doesNotContainAnyElementsOf(MachineApiRegistry.unreachable());
    }

    @Test
    void no_route_is_reachable_by_more_than_one_scope() {
        Set<Route> seen = new TreeSet<>();
        for (String scope : MachineApiRegistry.scopes()) {
            for (Route route : MachineApiRegistry.routesOf(scope)) {
                assertThat(seen.add(route))
                        .as("%s is granted by more than one scope; a route has exactly one", route)
                        .isTrue();
            }
        }
    }

    private Set<Route> classifiedRoutes() {
        return new TreeSet<>(MachineApiRegistry.classified());
    }

    /** The application's own route table, which is the only authority on what exists. */
    private Set<Route> apiRoutesFromTheRouteTable() {
        Set<Route> routes = new TreeSet<>();
        for (RequestMappingHandlerMapping mapping : webApplicationContext
                .getBeansOfType(RequestMappingHandlerMapping.class)
                .values()) {
            for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                        if (pattern.startsWith("/api/")) {
                            routes.add(new Route(method.name(), pattern));
                        }
                    }
                }
            }
        }
        return routes;
    }
}

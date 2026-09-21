package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The portal's client-side routes are also server-side routes, and the two lists are written in
 * different languages in different files.
 *
 * <p>A route the router registers and {@code SpaController} does not is invisible while the browser
 * navigates within the application — the router handles it without a request — and answers 404 the
 * instant anyone opens it cold. A pasted link is always cold. That is how the snapshot contents
 * route shipped broken in the change that added it: every unit test drove an in-memory router, and
 * only the real-browser suite reloading a deep link found it.
 *
 * <p>So this reads the router itself rather than restating its paths, and asks the running gateway
 * for each one. A new portal route now fails here until it is served, instead of failing for the
 * second approver who was sent a link.
 */
class SpaRoutesTests extends AbstractGatewayTest {

    private static final Path ROUTER = Path.of("src/main/frontend/src/main.tsx");

    /** `{ path: "/marketplaces/:name", element: ... }` — the router's own declaration. */
    private static final Pattern ROUTE = Pattern.compile("path:\\s*\"(/[^\"]*)\"");

    private static List<String> routerPaths() throws IOException {
        var matcher = ROUTE.matcher(Files.readString(ROUTER));
        var paths = new ArrayList<String>();
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    /** A concrete address for a parameterised route: `:name` and `:id` become something real. */
    private static String addressable(String routePath) {
        return routePath.replaceAll(":id\\b", "1").replaceAll(":[A-Za-z]+", "acme");
    }

    @Test
    void every_client_route_the_portal_registers_is_served_as_the_spa_entry() throws Exception {
        var paths = routerPaths();

        // The parse is load-bearing: a silent zero would make this suite pass forever.
        assertThat(paths)
                .as("routes parsed from %s", ROUTER)
                .hasSizeGreaterThanOrEqualTo(8)
                .contains("/", "/marketplaces", "/marketplaces/:name");

        for (var routePath : paths) {
            var address = addressable(routePath);
            mockMvc.perform(get(address).with(oidcLogin().idToken(token -> token.subject("root"))))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void a_deep_link_into_a_snapshots_files_is_served_cold_with_its_query_intact() throws Exception {
        // The address a first approver sends a second one (GW_INGEST_0032). Opened cold, it has to
        // reach the SPA rather than 404; the query string is the router's to read, not the server's.
        mockMvc.perform(get("/marketplaces/acme/snapshots/1/files")
                        .queryParam("path", "plugins/hello/skills/hello/SKILL.md")
                        .with(oidcLogin().idToken(token -> token.subject("root"))))
                .andExpect(status().isOk());
    }
}

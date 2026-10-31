package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.reqstool.annotations.SVCs;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * What a browser reads where the portal is not the answer (GW_AUTH_0067, GW_AUTH_0068): an address
 * nothing serves, a request that cannot be parsed, and the sign-in and sign-out pages — and that
 * every machine surface keeps answering exactly as it did.
 */
class BrowserPagesTests extends AbstractGatewayTest {

    /** What a browser sends when it navigates to a page. */
    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(oidcLogin().idToken(token -> token.subject("root")));
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0078"})
    void a_browser_asking_for_an_unknown_page_gets_the_portal_answered_not_found() throws Exception {
        var response = mockMvc.perform(signedIn(get("/nonexistent/route").header("Accept", BROWSER_ACCEPT)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn()
                .getResponse();

        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .as("the portal's entry, which renders page-not-found inside its shell")
                .contains("<div id=\"root\">")
                .doesNotContain("No static resource");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0078"})
    void every_other_client_and_every_machine_surface_keeps_its_problem_document() throws Exception {
        // Not a browser page request: a wildcard, JSON, and no Accept at all.
        for (String accept : List.of("*/*", "application/json", "")) {
            var request = signedIn(get("/nonexistent/route"));
            if (!accept.isEmpty()) {
                request.header("Accept", accept);
            }
            mockMvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("No static resource nonexistent/route."))
                    .andExpect(jsonPath("$.instance").value("/nonexistent/route"));
        }
        // A browser under a machine surface still reads that surface's own refusal.
        for (String path : List.of(
                "/api/v1/no-such-thing",
                "/git/no-such-thing",
                "/publish/no-such-thing",
                "/status/no-such-thing",
                "/hooks/no-such-thing",
                "/actuator/no-such-thing",
                "/v3/no-such-thing")) {
            var body = mockMvc.perform(signedIn(get(path).header("Accept", BROWSER_ACCEPT)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).as(path).doesNotContain("<div id=\"root\">");
        }
        // Matched on the decoded path, as the handlers are: an escaped prefix is still that surface.
        for (String path : List.of("/api/v1/no-such-thing", "/%61pi/v1/no-such-thing")) {
            mockMvc.perform(signedIn(get(URI.create(path)).header("Accept", BROWSER_ACCEPT)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0079"})
    void a_request_that_cannot_be_parsed_is_a_minimal_page_to_a_browser_and_plain_text_otherwise() throws Exception {
        String page = raw("/%zz", BROWSER_ACCEPT);
        assertThat(page).startsWith("HTTP/1.1 400");
        assertThat(header(page, "content-type")).startsWith("text/html");
        assertThat(page)
                .contains("Skills Gateway")
                .contains("Bad Request")
                .doesNotContainIgnoringCase("tomcat")
                .as("nothing is loaded from elsewhere")
                .doesNotContain("src=", "<link", "<script");

        String text = raw("/%zz", "*/*");
        assertThat(text).startsWith("HTTP/1.1 400");
        assertThat(header(text, "content-type")).startsWith("text/plain");
        assertThat(text).doesNotContain("<html").doesNotContainIgnoringCase("tomcat");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0081"})
    void without_a_session_the_sign_in_pages_say_so_and_claim_no_sign_out() throws Exception {
        for (String path : List.of("/login", "/login?logout", "/logout")) {
            String body = mockMvc.perform(get(path).header("Accept", BROWSER_ACCEPT))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(body)
                    .as(path)
                    .contains("Skills Gateway")
                    .contains("You are not signed in")
                    .doesNotContainIgnoringCase("have been signed out")
                    .doesNotContain("action=\"/logout\"")
                    .contains("href=\"/\"")
                    .doesNotContain("src=", "<link", "<script");
        }
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0081"})
    void with_a_session_the_pages_name_the_user_and_sign_out_carries_the_request_forgery_token() throws Exception {
        String signIn = mockMvc.perform(signedIn(get("/login?logout")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(signIn).contains("signed in as <strong>root</strong>").doesNotContain("not signed in");

        String signOut = mockMvc.perform(signedIn(get("/logout")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(signOut)
                .contains("signed in as <strong>root</strong>")
                .contains("<form method=\"post\" action=\"/logout\">")
                .containsPattern("<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\">")
                .doesNotContain("<script");
    }

    /** A request sent byte for byte: an address that cannot be decoded is one no HTTP client builds. */
    private String raw(String target, String accept) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nAccept: " + accept
                            + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            try (BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                return in.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    private static String header(String response, String name) {
        return response.lines()
                .takeWhile(line -> !line.isEmpty())
                .filter(line -> line.toLowerCase().startsWith(name + ":"))
                .map(line -> line.substring(name.length() + 1).trim())
                .findFirst()
                .orElse("");
    }
}

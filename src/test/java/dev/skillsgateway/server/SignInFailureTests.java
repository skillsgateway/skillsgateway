package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A failed browser sign-in is logged and answered with its reason, never with "Invalid credentials"
 * (GW_AUTH_0054). Driven over real HTTP: the sign-in is started for real so the callback carries a
 * state the gateway saved, and the provider is unreachable, so a callback with a code fails at the
 * token request.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Kept in lock-step with AbstractForwardedHeadersTest and SessionCookieTests so the three
        // share one application context (ContextBudgetTests).
        properties = {
            "spring.security.oauth2.client.registration.idp.client-id=test",
            "spring.security.oauth2.client.registration.idp.client-secret=test",
            "spring.security.oauth2.client.provider.idp.authorization-uri=https://idp.invalid/authorize",
            "spring.security.oauth2.client.provider.idp.token-uri=https://idp.invalid/token",
            "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.invalid/jwks",
            "skills-gateway.oidc.issuer=https://idp.invalid",
            "skills-gateway.data-dir=target/test-git-data",
            "skills-gateway.roles.admins=user",
            "spring.main.cloud-platform=none"
        })
class SignInFailureTests {

    private static final String CALLBACK = "/login/oauth2/code/idp";

    @LocalServerPort
    private int port;

    @Test
    @SVCs({"SVC_GW_AUTH_0054"})
    void a_callback_with_no_sign_in_in_progress_says_start_again(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get(CALLBACK + "?code=abc&state=nobody-saved-this", null);

        assertFailurePage(response, "authorization_request_not_found", "start again");
        assertLogged(output, "authorization_request_not_found");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0054"})
    void a_refusal_by_the_provider_says_the_provider_declined(CapturedOutput output) throws Exception {
        SignIn signIn = start();

        HttpResponse<String> response = get(
                CALLBACK + "?error=access_denied&error_description=no%0Aforged-line&state=" + signIn.state(),
                signIn.cookies());

        assertFailurePage(response, "access_denied", "declined");
        assertLogged(output, "access_denied");
        assertThat(output.getAll())
                .as("the provider's description is logged on one line, so it cannot forge a second entry")
                .doesNotContain("\nforged-line");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0054"})
    void an_answer_the_gateway_cannot_accept_says_so(CapturedOutput output) throws Exception {
        SignIn signIn = start();

        HttpResponse<String> response = get(CALLBACK + "?code=abc&state=" + signIn.state(), signIn.cookies());

        assertFailurePage(response, "invalid_token_response", "could not be accepted");
        assertLogged(output, "invalid_token_response");
    }

    /** A provider-supplied code is shown escaped, never as markup. */
    @Test
    @SVCs({"SVC_GW_AUTH_0054"})
    void a_provider_supplied_code_is_escaped(CapturedOutput output) throws Exception {
        SignIn signIn = start();

        HttpResponse<String> response =
                get(CALLBACK + "?error=%3Cscript%3Ex%3C%2Fscript%3E&state=" + signIn.state(), signIn.cookies());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    private record SignIn(String state, String cookies) {}

    /** Starts a real sign-in and returns the state the gateway saved and the cookies that carry it. */
    private SignIn start() throws Exception {
        HttpResponse<String> response = get("/oauth2/authorization/idp", null);
        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("location").orElseThrow();
        String state = Arrays.stream(URI.create(location).getRawQuery().split("&"))
                .filter(pair -> pair.startsWith("state="))
                .map(pair -> URLDecoder.decode(pair.substring("state=".length()), StandardCharsets.UTF_8))
                .findFirst()
                .orElseThrow();
        String cookies = response.headers().allValues("set-cookie").stream()
                .map(cookie -> cookie.split(";", 2)[0])
                .collect(Collectors.joining("; "));
        return new SignIn(java.net.URLEncoder.encode(state, StandardCharsets.UTF_8), cookies);
    }

    private HttpResponse<String> get(String path, String cookies) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET();
        if (cookies != null && !cookies.isEmpty()) {
            request.header("Cookie", cookies);
        }
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void assertFailurePage(HttpResponse<String> response, String code, String reason) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("content-type"))
                .get()
                .asString()
                .startsWith("text/html");
        assertThat(response.body())
                .as("the page names the reason class, the code, and a way to start again")
                .containsIgnoringCase(reason)
                .contains(code)
                .contains("href=\"/oauth2/authorization/idp\"")
                .doesNotContain("Invalid credentials");
    }

    private static void assertLogged(CapturedOutput output, String code) {
        assertThat(output.getAll().lines())
                .anySatisfy(line -> assertThat(line)
                        .contains("WARN")
                        .contains("registration=idp")
                        .contains("error=" + code));
    }
}

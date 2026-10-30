package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.auth.TokenService;
import io.github.reqstool.annotations.SVCs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/**
 * The anti-forgery token is the session's, not the cookie's (GW_AUTH_0058), and an unauthenticated
 * request leaves no session behind (GW_AUTH_0059), both asked of the running server over real HTTP.
 *
 * <p>Same context as {@link SessionCookieTests}, and for the same reason: no suite in it uses
 * MockMvc's {@code csrf()}, which replaces the web chain's token repository for the life of the
 * context. Here the real repository is the subject.
 *
 * <p>A signed-in session is written to the session store directly rather than by a sign-in: the
 * identity provider this context names is unreachable, and what a sign-in leaves behind is exactly
 * a stored security context, which is all the web chain reads.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Kept in lock-step with SessionCookieTests and AbstractForwardedHeadersTest (ContextBudgetTests).
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
class SessionBoundCsrfTests {

    /** Body-less, state-changing, administrator-only, and answers 200 when it gets through. */
    private static final String MUTATION = "/api/v1/catalog/rebuild";

    @LocalServerPort
    private int port;

    @Autowired
    private SessionRepository<? extends Session> sessions;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TokenService tokenService;

    @Test
    @SVCs({"SVC_GW_AUTH_0058"})
    void the_portal_flow_is_accepted_and_a_pair_the_session_was_never_issued_is_refused() throws Exception {
        Map<String, String> jar = signedIn();
        String issued = readToken(jar);

        assertThat(post(MUTATION, jar, issued).statusCode())
                .as("the portal's own flow: the cookie it was given, echoed in the header")
                .isEqualTo(200);

        String forged = UUID.randomUUID().toString();
        Map<String, String> forgedJar = new LinkedHashMap<>(jar);
        forgedJar.put("XSRF-TOKEN", forged);
        assertThat(post(MUTATION, forgedJar, forged).statusCode())
                .as("a cookie and header that agree, but name a token this session was never issued")
                .isEqualTo(403);
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0059"})
    void a_token_issued_to_one_session_is_refused_on_another() throws Exception {
        Map<String, String> first = signedIn();
        String firstToken = readToken(first);
        Map<String, String> second = signedIn();
        String secondToken = readToken(second);
        assertThat(secondToken).isNotEqualTo(firstToken);

        Map<String, String> crossed = new LinkedHashMap<>(second);
        crossed.put("XSRF-TOKEN", firstToken);
        assertThat(post(MUTATION, crossed, firstToken).statusCode())
                .as("the first session's token, presented as cookie and header on the second session")
                .isEqualTo(403);

        assertThat(post(MUTATION, second, secondToken).statusCode())
                .as("the second session's own token")
                .isEqualTo(200);
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0061"})
    void an_unauthenticated_request_is_answered_without_creating_a_session() throws Exception {
        long before = storedSessions();

        for (String path : List.of("/api/v1/me", "/status/v1/snapshots", "/git/nothing.git/info/refs")) {
            HttpResponse<String> response =
                    send(HttpRequest.newBuilder(uri(path)).GET(), Map.of());
            assertThat(response.statusCode()).as(path).isEqualTo(401);
            assertThat(sessionCookies(response))
                    .as("%s sets no session cookie", path)
                    .isEmpty();
        }
        // Outside /api/** too: a GET that is not a page navigation is answered 401, not redirected.
        for (String accept : List.of("*/*", "application/json", "text/event-stream")) {
            HttpResponse<String> response = send(
                    HttpRequest.newBuilder(uri("/approvals"))
                            .header("Accept", accept)
                            .GET(),
                    Map.of());
            assertThat(response.statusCode()).as("Accept: %s", accept).isEqualTo(401);
            assertThat(sessionCookies(response))
                    .as("a GET accepting %s sets no session cookie", accept)
                    .isEmpty();
        }
        String forged = UUID.randomUUID().toString();
        HttpResponse<String> mutation = post(MUTATION, Map.of("XSRF-TOKEN", forged), forged);
        assertThat(mutation.statusCode()).as("an anonymous write").isIn(401, 403);
        assertThat(sessionCookies(mutation))
                .as("an anonymous write sets no session cookie")
                .isEmpty();

        assertThat(storedSessions()).as("the session store did not grow").isEqualTo(before);
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0061"})
    void a_request_authenticated_by_its_own_credential_needs_no_token_and_creates_no_session() throws Exception {
        long before = storedSessions();
        String machine = tokenService
                .createMachineCredential(
                        // The administrator's name, so the route's own role check admits it and a
                        // refusal could only be the chain's.
                        "user",
                        "csrf-probe-" + UUID.randomUUID(),
                        List.of("catalog:rebuild"),
                        Instant.now().plus(1, ChronoUnit.DAYS),
                        "user")
                .token();
        String pat =
                tokenService.create("user", "csrf-probe-" + UUID.randomUUID()).token();

        HttpResponse<String> rebuilt = send(
                HttpRequest.newBuilder(uri(MUTATION))
                        .header("Authorization", "Bearer " + machine)
                        .POST(HttpRequest.BodyPublishers.noBody()),
                Map.of());
        assertThat(rebuilt.statusCode())
                .as("a machine credential needs no anti-forgery token")
                .isEqualTo(200);
        assertThat(sessionCookies(rebuilt)).isEmpty();

        String basic = Base64.getEncoder().encodeToString(("user:" + pat).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> fetched = send(
                HttpRequest.newBuilder(uri("/git/nothing.git/info/refs?service=git-upload-pack"))
                        .header("Authorization", "Basic " + basic)
                        .GET(),
                Map.of());
        assertThat(fetched.statusCode()).as("the PAT authenticated").isNotIn(401, 403);
        assertThat(sessionCookies(fetched)).isEmpty();

        assertThat(storedSessions()).as("the session store did not grow").isEqualTo(before);
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0062"})
    void the_browser_sign_in_still_creates_the_session_that_holds_its_authorization_request() throws Exception {
        long before = storedSessions();

        HttpResponse<String> response =
                send(HttpRequest.newBuilder(uri("/oauth2/authorization/idp")).GET(), Map.of());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("location"))
                .hasValueSatisfying(location -> assertThat(location).startsWith("https://idp.invalid/authorize"));
        assertThat(sessionCookies(response)).hasSize(1);
        assertThat(storedSessions()).isEqualTo(before + 1);
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0062"})
    void a_page_navigation_is_redirected_to_the_sign_in_and_kept_to_return_to() throws Exception {
        long before = storedSessions();

        HttpResponse<String> response = send(
                HttpRequest.newBuilder(uri("/approvals"))
                        .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                        .GET(),
                Map.of());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("location"))
                .hasValueSatisfying(location -> assertThat(location).endsWith("/oauth2/authorization/idp"));
        assertThat(sessionCookies(response)).as("the page is kept to return to").hasSize(1);
        assertThat(storedSessions()).isEqualTo(before + 1);
    }

    /** What a completed sign-in leaves in the store: a session holding an authenticated context. */
    private Map<String, String> signedIn() {
        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken(
                "id-token", now, now.plus(1, ChronoUnit.HOURS), Map.of("sub", "user", "iss", "https://idp.invalid"));
        var authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
        var authentication =
                new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, idToken), authorities, "idp");
        String id = store(sessions, new SecurityContextImpl(authentication));
        Map<String, String> jar = new LinkedHashMap<>();
        jar.put("SESSION", Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8)));
        return jar;
    }

    private static <S extends Session> String store(SessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session.getId();
    }

    /** A read, as the portal makes on load; the token cookie it is answered with is kept in the jar. */
    private String readToken(Map<String, String> jar) throws Exception {
        HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/v1/me")).GET(), jar);
        assertThat(me.statusCode())
                .as("the session is signed in: %s", me.body())
                .isEqualTo(200);
        assertThat(jar).containsKey("XSRF-TOKEN");
        return jar.get("XSRF-TOKEN");
    }

    private HttpResponse<String> post(String path, Map<String, String> jar, String headerToken) throws Exception {
        return send(
                HttpRequest.newBuilder(uri(path))
                        .header("X-XSRF-TOKEN", headerToken)
                        .POST(HttpRequest.BodyPublishers.noBody()),
                jar);
    }

    /** One request, with the jar's cookies sent and whatever the response sets kept in a mutable jar. */
    private HttpResponse<String> send(HttpRequest.Builder request, Map<String, String> jar) throws Exception {
        if (!jar.isEmpty()) {
            request.header(
                    "Cookie",
                    jar.entrySet().stream()
                            .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                            .collect(Collectors.joining("; ")));
        }
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        if (jar instanceof LinkedHashMap<String, String> mutable) {
            for (String cookie : response.headers().allValues("set-cookie")) {
                String[] pair = cookie.split(";", 2)[0].split("=", 2);
                if (pair.length == 2 && !pair[1].isEmpty()) {
                    mutable.put(pair[0], pair[1]);
                } else {
                    mutable.remove(pair[0]);
                }
            }
        }
        return response;
    }

    private static List<String> sessionCookies(HttpResponse<?> response) {
        return response.headers().allValues("set-cookie").stream()
                .filter(cookie -> cookie.startsWith("SESSION="))
                .toList();
    }

    private long storedSessions() {
        return jdbc.sql("SELECT count(*) FROM spring_session").query(Long.class).single();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}

package dev.skillsgateway.server.auth;

import dev.skillsgateway.server.api.BrandedPage;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The gateway's own sign-in and sign-out pages, in place of the framework's generated ones
 * (GW_AUTH_0068). Each states the reading session as it is — signed in and as whom, or not — and
 * never reports a sign-out the reader may not have performed. Signing in itself is unchanged: an
 * unauthenticated portal request still goes straight to the identity provider, which is what the
 * "Sign in" link here does too. Signing out still posts to the framework's {@code /logout}.
 */
@Controller
public class SignInPagesController {

    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final boolean devInsecureAuth;

    public SignInPagesController(SkillsGatewayProperties properties) {
        this.devInsecureAuth = properties.devInsecureAuth();
    }

    @GetMapping("/login")
    @Requirements({"GW_AUTH_0068"})
    public ResponseEntity<String> signIn(Authentication authentication) {
        String name = signedIn(authentication);
        if (name == null) {
            return page("Not signed in", notSignedIn());
        }
        return page(
                "Signed in",
                "<p>You are signed in as <strong>%s</strong>.</p>\n".formatted(BrandedPage.escape(name))
                        + "<div class=\"actions\"><a href=\"/\">Open the portal</a>"
                        + (devInsecureAuth ? "" : "<a href=\"/logout\">Sign out</a>")
                        + "</div>");
    }

    @GetMapping("/logout")
    @Requirements({"GW_AUTH_0045", "GW_AUTH_0068"})
    public ResponseEntity<String> signOut(Authentication authentication, CsrfToken csrfToken) {
        String name = signedIn(authentication);
        if (name == null) {
            return page("Not signed in", notSignedIn());
        }
        if (devInsecureAuth) {
            // As the user menu says: the principal is invented per request, so there is nothing to end.
            return page(
                    "No session to end",
                    "<p>The development escape hatch is on — there is no session to end.</p>\n"
                            + "<div class=\"actions\"><a href=\"/\">Back to the portal</a></div>");
        }
        return page(
                "Sign out",
                "<p>You are signed in as <strong>%s</strong>. Signing out ends this browser's session.</p>\n"
                                .formatted(BrandedPage.escape(name))
                        + "<div class=\"actions\"><form method=\"post\" action=\"/logout\">"
                        + "<input type=\"hidden\" name=\"%s\" value=\"%s\">"
                                .formatted(
                                        BrandedPage.escape(csrfToken.getParameterName()),
                                        BrandedPage.escape(csrfToken.getToken()))
                        + "<button type=\"submit\">Sign out</button></form>"
                        + "<a href=\"/\">Back to the portal</a></div>");
    }

    private static String notSignedIn() {
        return "<p>You are not signed in.</p>\n"
                + "<div class=\"actions\"><a href=\"/\">Sign in with your identity provider</a></div>";
    }

    /** The signed-in principal's name, or null for an anonymous request. */
    private static String signedIn(Authentication authentication) {
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getName();
    }

    private static ResponseEntity<String> page(String heading, String body) {
        return ResponseEntity.ok().contentType(HTML).body(BrandedPage.render(heading, body));
    }
}

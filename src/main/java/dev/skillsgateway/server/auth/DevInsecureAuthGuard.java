package dev.skillsgateway.server.auth;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * Startup guard for the development escape hatch (GW_0110).
 *
 * <p>{@code skills-gateway.dev-insecure-auth=true} opens the whole web surface — every {@code
 * /api/**}, {@code /actuator/**} and {@code /docs} request — and attributes it to a synthetic
 * principal named "dev". It is opt-in and off by default, which is the primary control; this bean
 * is the second one. It refuses to start when the flag is on somewhere the flag cannot belong.
 *
 * <p>The signal is a <em>configured identity provider</em>. The escape hatch exists for exactly one
 * situation — a developer with no identity provider to log in to — so a deployment that has one
 * wired up has contradicted itself: the login it configured is the login it just switched off. A
 * configured provider is also close to a necessary condition for a real deployment, because a
 * gateway without one cannot log a single user in. Concretely: refusal happens when the flag is on
 * and either a client registration carries a client id other than the shipped {@code change-me}
 * placeholder, or a provider endpoint points anywhere other than the shipped {@code idp.invalid}
 * placeholder host, or {@code skills-gateway.oidc.issuer} is pinned.
 *
 * <p>Signals deliberately <em>not</em> used, because each would refuse a documented local loop: the
 * bind address ({@code server.address} is unset in a container and on a laptop alike, so
 * "non-loopback" describes every local run), the datasource host (the documented Compose loop
 * points at the host {@code postgres}), and the active Spring profile (this application ships none
 * that distinguishes a deployment).
 *
 * <p>There is deliberately no override property: an override would be a flag whose only purpose is
 * to switch off the check on the flag, and the actionable fix — stop setting {@code
 * dev-insecure-auth} — is always available.
 */
@Component
@Requirements({"GW_0110"})
public class DevInsecureAuthGuard {

    /** The client id {@code application.yaml} ships when no {@code SGW_OIDC_CLIENT_ID} is set. */
    public static final String PLACEHOLDER_CLIENT_ID = "change-me";

    /** The host every provider endpoint in {@code application.yaml} names until it is configured. */
    public static final String PLACEHOLDER_PROVIDER_HOST = "idp.invalid";

    /**
     * What the guard saw, kept for anything that wants to report the deployment's shape. Empty
     * whenever the escape hatch is off — the guard looks at nothing it was not asked to.
     */
    private final List<String> signals;

    public DevInsecureAuthGuard(
            SkillsGatewayProperties properties, ObjectProvider<ClientRegistrationRepository> registrations) {
        this.signals = properties.devInsecureAuth()
                ? List.copyOf(identityProviderSignals(properties.oidc().issuer(), registrations.getIfAvailable()))
                : List.of();
        if (!signals.isEmpty()) {
            throw new IllegalStateException(refusal(signals));
        }
    }

    /** The identity-provider signals this startup was judged on; empty when it was not judged. */
    public List<String> signals() {
        return signals;
    }

    /**
     * The evidence that an identity provider is configured, as human-readable signals. Empty means
     * none was found — the unconfigured state a development loop is in.
     */
    static List<String> identityProviderSignals(String pinnedIssuer, ClientRegistrationRepository registrations) {
        List<String> signals = new ArrayList<>();
        if (pinnedIssuer != null && !pinnedIssuer.isBlank()) {
            signals.add("skills-gateway.oidc.issuer is pinned to " + pinnedIssuer);
        }
        // The repository interface is lookup-only; the in-memory implementation Boot builds from
        // configuration is also Iterable, which is the only way to see every registration.
        if (registrations instanceof Iterable<?> iterable) {
            for (Object candidate : iterable) {
                if (candidate instanceof ClientRegistration registration) {
                    signals.addAll(signalsOf(registration));
                }
            }
        }
        return signals;
    }

    private static List<String> signalsOf(ClientRegistration registration) {
        List<String> signals = new ArrayList<>();
        String id = registration.getRegistrationId();
        String clientId = registration.getClientId();
        if (clientId != null && !clientId.isBlank() && !PLACEHOLDER_CLIENT_ID.equals(clientId)) {
            signals.add("the OIDC client registration '" + id + "' carries a real client id");
        }
        ClientRegistration.ProviderDetails provider = registration.getProviderDetails();
        addEndpointSignal(signals, id, "authorization-uri", provider.getAuthorizationUri());
        addEndpointSignal(signals, id, "token-uri", provider.getTokenUri());
        addEndpointSignal(signals, id, "jwk-set-uri", provider.getJwkSetUri());
        addEndpointSignal(signals, id, "issuer-uri", provider.getIssuerUri());
        return signals;
    }

    /** A provider endpoint counts as configured once it names a host other than the placeholder. */
    private static void addEndpointSignal(List<String> signals, String id, String property, String uri) {
        if (uri == null || uri.isBlank()) {
            return;
        }
        String host;
        try {
            host = URI.create(uri).getHost();
        } catch (IllegalArgumentException malformed) {
            // Unparseable is not evidence of a deployment; the OAuth2 client complains on its own.
            return;
        }
        if (host != null && !PLACEHOLDER_PROVIDER_HOST.equalsIgnoreCase(host)) {
            signals.add("the OIDC provider '" + id + "' has a real " + property + " (" + uri + ")");
        }
    }

    private static String refusal(List<String> signals) {
        String evidence = signals.stream().map(signal -> "  - " + signal).collect(Collectors.joining("\n"));
        return """
                skills-gateway.dev-insecure-auth=true refuses to start here.

                That setting makes the ENTIRE web surface unauthenticated -- every /api/**, \
                /actuator/** and /docs request -- and attributes it to a synthetic principal named \
                "dev". It exists for a local development loop that has no identity provider, but \
                this gateway has one configured:

                %s

                Resolve it one of two ways:
                  * a real deployment: remove skills-gateway.dev-insecure-auth (it defaults to \
                false) and log in through the configured identity provider;
                  * genuinely local development: leave the identity-provider configuration unset, \
                so the shipped placeholders (client id "%s", provider host "%s") stay in force.""".formatted(evidence, PLACEHOLDER_CLIENT_ID, PLACEHOLDER_PROVIDER_HOST);
    }
}

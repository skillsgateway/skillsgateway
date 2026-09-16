package dev.skillsgateway.server;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * A stand-in identity provider: a real RSA key pair, a real JWKS endpoint, and a token builder that
 * can mint every token in this change's failure model.
 *
 * <p>Static and process-wide, because the key set's URL has to be known before the Spring context
 * starts — {@code @DynamicPropertySource} reads {@link #jwkSetUri()} — and because one provider
 * serves every suite that needs one.
 *
 * <p><b>Two key pairs, and the second one is the point.</b> Only {@link #SIGNING_KEY} is published
 * at the key set endpoint. {@link #FOREIGN_KEY} is a well-formed RSA key the gateway has never
 * heard of, which is how a token with a valid structure and an unverifiable signature is produced
 * without hand-corrupting bytes — corrupted bytes fail at parsing, which proves the parser works
 * and nothing about the signature check.
 */
final class IdpBearerFixture {

    /** The issuer the gateway is told to pin. */
    static final String ISSUER = "https://idp.test.invalid/skills-gateway";

    /** An issuer signed by the same keys — a different tenant of the same endpoint. */
    static final String FOREIGN_ISSUER = "https://idp.test.invalid/somebody-else";

    /** The audience the gateway is told to require; the OAuth2 client id in the shared context. */
    static final String AUDIENCE = "test";

    private static final RSAKey SIGNING_KEY = generate("sgw-test-key");
    private static final RSAKey FOREIGN_KEY = generate("sgw-unknown-key");
    private static final HttpServer SERVER = startKeySetServer();

    private IdpBearerFixture() {}

    static String jwkSetUri() {
        return "http://" + SERVER.getAddress().getAddress().getHostAddress() + ":"
                + SERVER.getAddress().getPort() + "/jwks";
    }

    /** A token that should be accepted: right issuer, right audience, valid now, signed by the published key. */
    static String validToken(String subject) {
        return token(subject, claims -> {});
    }

    /**
     * A token with every default correct, then whatever the caller changes. Signed by the published
     * key unless {@link #tokenSignedByAnUnknownKey} is used instead.
     */
    static String token(String subject, Consumer<JWTClaimsSet.Builder> customize) {
        return sign(claims(subject, customize), SIGNING_KEY);
    }

    /** Structurally valid, signed by a key the gateway's key set does not contain. */
    static String tokenSignedByAnUnknownKey(String subject) {
        return sign(claims(subject, claim -> {}), FOREIGN_KEY);
    }

    private static JWTClaimsSet claims(String subject, Consumer<JWTClaimsSet.Builder> customize) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(now.minusSeconds(30)))
                .notBeforeTime(Date.from(now.minusSeconds(30)))
                .expirationTime(Date.from(now.plusSeconds(600)));
        customize.accept(builder);
        return builder.build();
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the test token", e);
        }
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate a test key", e);
        }
    }

    private static HttpServer startKeySetServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            // Only the signing key is published. A token signed by the foreign key is therefore
            // unverifiable rather than merely unexpected.
            byte[] body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("could not start the test key set endpoint", e);
        }
    }
}

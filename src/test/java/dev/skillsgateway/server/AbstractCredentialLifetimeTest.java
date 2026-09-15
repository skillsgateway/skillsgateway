package dev.skillsgateway.server;

import org.springframework.test.context.TestPropertySource;

/**
 * The two credential-lifetime postures the shared fixture deliberately does not have, declared
 * together so they cost one application context rather than two (#305).
 *
 * <p>They are independent knobs on independent code paths: {@code max-ttl} is validated only where
 * a caller supplies an expiry — personal access tokens and machine credentials — while a session
 * credential's deadline is the gateway's own, taken from {@code session-ttl} and never checked
 * against the cap. So neither posture is observable from the other's assertions, and putting them
 * in one context merges no claim.
 *
 * <p>What the merge does mean is that the shared fixture's {@code newPat()} — which asks for a
 * token with no expiry at all — is refused here, because that is exactly what a cap forbids. That
 * is a loud failure at the point of use, not a silent one, and a subclass that needs a credential
 * has to name a deadline the way an operator under a cap would.
 */
@TestPropertySource(properties = {"skills-gateway.tokens.max-ttl=30d", "skills-gateway.tokens.session-ttl=1ms"})
abstract class AbstractCredentialLifetimeTest extends AbstractGatewayTest {}

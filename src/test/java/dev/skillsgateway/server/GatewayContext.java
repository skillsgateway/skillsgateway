package dev.skillsgateway.server;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The suite's shared application context, declared once so that a test which cannot extend {@link
 * AbstractGatewayTest} can still share it rather than start a second one.
 *
 * <p>The cache key Spring computes is the property array and the imported configuration, so a class
 * carrying this annotation lands on the same key as every {@code AbstractGatewayTest} subclass —
 * one context, one PostgreSQL container, one object store. Copying the property list instead would
 * work only until the two copies drifted by a character, at which point the suite would quietly
 * build an extra context (#305).
 *
 * <p>Almost every test should get this by extending {@link AbstractGatewayTest}, which carries the
 * annotation along with the fixtures. Naming it directly is for the rare class whose single
 * inheritance slot is already spent on a shared assertion suite.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Dummy OIDC registration with explicit provider details: no discovery at startup.
            "spring.security.oauth2.client.registration.idp.client-id=test",
            "spring.security.oauth2.client.registration.idp.client-secret=test",
            "spring.security.oauth2.client.registration.idp.authorization-grant-type=authorization_code",
            "spring.security.oauth2.client.registration.idp.redirect-uri={baseUrl}/login/oauth2/code/idp",
            "spring.security.oauth2.client.provider.idp.authorization-uri=https://idp.invalid/authorize",
            "spring.security.oauth2.client.provider.idp.token-uri=https://idp.invalid/token",
            "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.invalid/jwks",
            "skills-gateway.data-dir=target/test-git-data",
            // Authorization is always enforced and a gateway with no configured administrator
            // refuses to start (GW_AUTH_0025, GW_AUTH_0026), so the shared context names the principals its
            // suites act as: "user" is what Spring Security's oidcLogin() invents by default, and
            // "root" and "alice" are the named subjects the non-authorization suites use.
            //
            // This does blunt the shared context as a place to catch a missing require* call, which
            // is why the deny-by-default walk that guards against exactly that lives in
            // RoleEnforcementTests: its own context, its own admin, and a principal that is not one.
            "skills-gateway.roles.admins=user,root,alice",
            // file is allowed only here so HTTP-level tests can register local fixtures.
            "skills-gateway.allowed-url-schemes=http,https,file",
            // Webhook dispatch is driven explicitly by the tests: the poller is off and the
            // backoff is compressed so retry behaviour is observable without long sleeps.
            "skills-gateway.webhooks.enabled=false",
            "skills-gateway.webhooks.base-backoff=100ms",
            "skills-gateway.webhooks.max-backoff=1s",
            "skills-gateway.webhooks.max-attempts=3",
            "skills-gateway.webhooks.timeout=2s",
            // Audit export passes are driven explicitly by the tests, and the commit-settling lag
            // is removed so an entry appended by the test is immediately exportable.
            "skills-gateway.audit-export.enabled=false",
            "skills-gateway.audit-export.lag=0s",
            // Retention passes are driven explicitly by the tests, and the age thresholds are
            // compressed so a snapshot ingested by a test is immediately eligible. Every retention
            // test scopes its pass to its own marketplace, so no other test's fixtures are reached.
            "skills-gateway.retention.enabled=false",
            "skills-gateway.retention.defaults.held-max-age=1ms",
            "skills-gateway.retention.defaults.superseded-min-age=0s",
            "skills-gateway.retention.defaults.min-idle=1h",
            "skills-gateway.retention.defaults.restore-window=1h",
            // Re-vetting passes are driven explicitly by the tests: the sweep is off so no
            // background pass can re-vet another test's fixtures, and the cadence is removed so a
            // snapshot vetted moments ago is immediately due. The mode is left at its production
            // default (warn); RevetEnforceTests overrides it, which is the whole point of the two
            // classes being separate.
            "skills-gateway.vetting.revet.enabled=false",
            "skills-gateway.vetting.revet.cadence=0s",
            // Sync sweeps are driven explicitly by the tests: a live background sweep would ingest
            // other tests' scheduled fixtures mid-run.
            "skills-gateway.sync.enabled=false",
            // Every suite here ingests the same fixture plugin, "hello", into a marketplace of its own
            // and approves it, all in one database; the name-collision rule would refuse the second of
            // those approvals in the run. Its enforcing default is exercised in its own context by
            // AbstractNameCollisionTest, whose fixtures carry unique plugin names.
            "skills-gateway.approval.name-collision.enabled=false"
        })
@Import(SyncMvcAsyncTestConfiguration.class)
public @interface GatewayContext {}

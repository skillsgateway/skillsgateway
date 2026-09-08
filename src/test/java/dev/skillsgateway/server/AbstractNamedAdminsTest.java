package dev.skillsgateway.server;

import org.springframework.test.context.TestPropertySource;

/**
 * One context for the suites whose only configuration difference was the <em>name</em> of their
 * administrator.
 *
 * <p>A gateway with no configured administrator refuses to start (GW_AUTH_0026 — A gateway with no
 * administrator refuses to start), so a suite that acts as a principal of its own has to name it.
 * Declaring that name per class made each of them a distinct context cache key, and so a distinct
 * application context, connection pool, embedded Tomcat and PostgreSQL container — for a difference
 * none of their assertions can observe. Sharing the declaration collapses them to one.
 *
 * <p>The names stay distinct; only the declaration is shared. Distinct names are what keeps each
 * suite's actors unambiguous in the audit ledger these suites now share, and every assertion below
 * that reads more than one row is already filtered to a fixture the suite named itself.
 *
 * <p>This list is load-bearing in the other direction too. Three suites in this package assert that
 * a principal holds no role <em>at all</em>, and each of them is only honest while its principal is
 * absent from every administrator list its context can see: {@code dev} in {@code
 * EscapeHatchRoleNegativeTests}, {@code mallory} in {@code RoleEnforcementTests} and {@code
 * just-logged-in} in {@code MachineCredentialAdminTests}. Adding a name here is safe; adding one of
 * those three would turn a deny assertion into a tautology.
 *
 * <p>{@code RoleEnforcementTests} is deliberately not one of the subclasses. Its job is the
 * deny-by-default walk over every mutating route, which is only honest in a context whose
 * administrator list it controls entirely — so it keeps its own, and pays for its own context.
 */
@TestPropertySource(
        properties = {"skills-gateway.roles.admins=auditor,dana,ingrid,owner,rachel,root,scope-walk-machine,solo"})
abstract class AbstractNamedAdminsTest extends AbstractGatewayTest {}

package dev.skillsgateway.server;

import org.springframework.test.context.TestPropertySource;

/**
 * One context for the suites that verify claim-derived roles (GW_AUTH_0015 — Roles derived from
 * identity provider claims).
 *
 * <p>The administrator list is deliberately one name. A claim-mapping suite's whole argument is that
 * a privilege held without a grant row came through the mapper, and that argument is only available
 * in a context where the bootstrap administrators are few and named. The shared base context's
 * {@code user,root,alice} would supply privilege by another route.
 *
 * <p>{@code gw-approvers-ghost} maps to a marketplace that is never registered: a mapping is allowed
 * to run ahead of the estate, and must confer nothing while it does.
 *
 * <p>A mapping added here is visible to every subclass, so add one only for a claim value no
 * subclass asserts the absence of — an unexpected mapping is indistinguishable from a mapper that
 * over-grants.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.roles.admins=root",
            "skills-gateway.roles.claim=groups",
            "skills-gateway.roles.mappings[0].claim-value=gw-admins",
            "skills-gateway.roles.mappings[0].role=admin",
            "skills-gateway.roles.mappings[1].claim-value=gw-approvers-a",
            "skills-gateway.roles.mappings[1].role=approver",
            "skills-gateway.roles.mappings[1].marketplace=claim-approver-mkt",
            "skills-gateway.roles.mappings[2].claim-value=gw-auditors",
            "skills-gateway.roles.mappings[2].role=auditor",
            "skills-gateway.roles.mappings[3].claim-value=gw-approvers-ghost",
            "skills-gateway.roles.mappings[3].role=approver",
            "skills-gateway.roles.mappings[3].marketplace=claim-unregistered-mkt"
        })
abstract class AbstractClaimMappingTest extends AbstractGatewayTest {}

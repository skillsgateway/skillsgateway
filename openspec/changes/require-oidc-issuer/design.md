# Design: require-oidc-issuer

## Context

See proposal.md for why. Today `SecurityConfig.idTokenDecoderFactory` reads
`skills-gateway.oidc.issuer`, logs a warning when it is blank (outside the
development escape hatch) and builds the ID-token validator with or without
an issuer comparison (`OidcIdTokenValidation`). `DevInsecureAuthGuard`
already answers "is an identity provider configured" through
`identityProviderSignals`: a client id other than `change-me`, or a provider
endpoint whose host is not `idp.invalid`, or a pinned issuer.

## Goals / Non-Goals

**Goals:** a configured provider with a blank issuer fails startup with a
message that names the property and what it was decided on; every case that
starts today and has an issuer, or has no provider, still starts.

**Non-Goals:** no opt-out property (decided on #546: no legitimate
unchecked deployment exists, and a leaf would raise the configuration
budget); no change to how a pinned issuer is compared; no change to the
facade's identity-provider bearer path, which already refuses on this
condition.

## Decisions

### D1. The refusal lives with the decoder it protects, in its own configuration class

The decoder factory bean moves from `SecurityConfig` to
`IdTokenDecoderConfiguration` (same package, same bean name), which also makes
the refusal. Keeping the check beside the one bean that consumes the issuer
means nothing can build the login's validator without passing it.
*Alternatives:* throwing inside `SecurityConfig` (as the decision notes
sketched) cannot be exercised as a real context without the whole security
configuration — HTTP security, PAT and machine-credential providers — which
would force a new counted Spring context; putting it in `DevInsecureAuthGuard`
would give that guard a second, unrelated job under a name that describes
only the first.

### D2. "Configured" is exactly the escape-hatch guard's definition

The check calls `DevInsecureAuthGuard.identityProviderSignals(null,
registrations)`. One definition of "a provider is configured" for both
guards means the placeholders `application.yaml` ships are the only
unconfigured state, and `DevInsecureAuthGuardTests` already pins those
placeholders to the shipped file.

### D3. Under the escape hatch the check defers to the hatch's own guard

With `dev-insecure-auth=true` a configured provider is already refused by
`DevInsecureAuthGuard`, whose message is the one that matters (the hatch
opens the whole web surface). Skipping this check under the hatch keeps that
message from being masked by an issuer complaint, whichever bean is created
first. With the hatch on and the placeholders in force there are no signals,
so the skip changes nothing else.

### D4. Test contexts pin a placeholder issuer

The shared context and the forwarded-headers / session-cookie pair configure
`client-id=test`, which is a configured provider. Each gets
`skills-gateway.oidc.issuer=https://idp.invalid` added at the same position;
lists that were equal stay equal, so the context count does not move. The
tests never complete a real login, so the value is never compared.

## Old-coder SPEC (Tier 3: authentication trust boundary)

Spec approval: not obtained (autonomous run; the decision on #546 was taken
for the owner). Isolation: git worktree `.claude/worktrees/oidc-issuer` from
`origin/main`, because the main checkout belongs to other sessions. No new
dependencies. Helm is not installed: a throwaway binary in the session
scratchpad is used for `helm lint`/`helm template` only, outside the repo.

Test class `OidcIssuerRequiredTests`, `ApplicationContextRunner` with Boot's
`OAuth2ClientAutoConfiguration` and the real `IdTokenDecoderConfiguration`,
starting from the shipped placeholders:

| # | Given | Then |
|---|---|---|
| S1 | real client id, no issuer | startup fails, `IllegalStateException`, message names `skills-gateway.oidc.issuer` and "carries a real client id" and both ways out |
| S2 | placeholder client id, real `jwk-set-uri`, no issuer | fails, message names "real jwk-set-uri" |
| S3 | real client id, issuer of only whitespace | fails (blank is unset) |
| S4 | real client id and endpoints, issuer pinned | starts; the decoder factory bean exists |
| S5 | shipped placeholders, no issuer | starts |
| S6 | escape hatch on, placeholders, no issuer | starts |
| S7 | escape hatch on, real client id, no issuer, `DevInsecureAuthGuard` present | fails with the hatch's refusal, not the issuer's |
| S8 | escape hatch on, real client id, no issuer, guard absent | this check does not fire (D3) |

Invariants (must NOT change): an existing SVC test is not weakened;
`ContextBudgetTests` and `ConfigSurfaceBudgetTests` budgets unchanged; the
issuer comparison itself (`OidcIdTokenValidationTests`) unchanged; OpenAPI
unchanged; e2e real login still succeeds with the issuer pinned (proves the
mock provider's `iss` equals the pinned value).

Failure model: (F1) the check is skipped when a provider is configured —
S1–S3; (F2) it fires on the unconfigured state and breaks local runs — S5, S6;
(F3) it masks the hatch's refusal — S7, S8; (F4) the harness issuer does not
match the mock's `iss`, so the e2e login fails — the e2e gate; (F5) the chart
renders without an issuer — `helm template` negative control.

## Risks / Trade-offs

- [A deployment that never set the issuer stops starting] → declared
  `BREAKING CHANGE`; the refusal says exactly what to set; pre-1.0.
- [Operator pins a slightly wrong issuer (trailing slash, v1 vs v2
  endpoint)] → every login then fails loudly rather than starting open; the
  docs point at the provider's discovery document's `issuer` value.

## Migration Plan

Set `SKILLSGATEWAY_OIDC_ISSUER` (chart: `oidc.issuer`) to the provider's
`issuer` from `/.well-known/openid-configuration` before upgrading. Rollback
is the previous image, which ignores nothing new.

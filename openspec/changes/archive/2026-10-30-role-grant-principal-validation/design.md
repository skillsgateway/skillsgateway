# Design: role-grant-principal-validation

## Context

See proposal.md, "Why". A grant row is written only by `RoleService.grant`,
which both the grants API and `EstateReconciler.reconcileGrant` call; the
reconciler first asks `RoleService.rolesOf` whether the grant already exists
and returns "unchanged" without calling `grant` if it does. `ClaimRoleMapper`
trims each configured claim value at startup and each token value at match
time, and accepts a lone string as a one-element list.

## Goals / Non-Goals

**Goals:** what an administrator grants is byte-for-byte what a session must
present; what a mapping names is byte-for-byte what a token must carry.

**Non-goals:**

- `skills-gateway.roles.admins`. It is already matched exactly and is not a
  grant; a padded entry matches nobody. Left as it is.
- Principals elsewhere (tokens, machine credentials, the ledger's actor). They
  come from an authenticated session, not from an administrator's input.
- Repairing stored rows. Pre-1.0 there is no deployed database (AGENTS.md).

## Decisions

### One rule, `RolePrincipals`, in the `roles` package

A small final class with a pure `problem(String)` that returns why a value is
malformed, or empty. `RoleService.grant` applies it with the length bound;
`ClaimRoleMapper` applies the same form rule, without the length bound, to each
configured claim value. One rule keeps the API, the estate and the mapping table
from drifting apart.

*Alternative:* Bean Validation on `GrantRequest`. Rejected: the estate does not
go through the controller, and the check must run in the one shared path.

### What is malformed

- Blank (already refused).
- A first or last code point that is whitespace: `Character.isWhitespace` or
  `Character.isSpaceChar`. The second covers the no-break spaces, which `strip()`
  leaves in place. Interior spaces stay allowed, because the principal claim is
  configurable (`GW_AUTH_0017 — Enterprise identity-provider session integrity`)
  and a display-style name can contain one.
- Anywhere: a control (`Cc`), format (`Cf`, which holds the bidi controls,
  zero-width characters and the byte-order mark), line separator (`Zl`),
  paragraph separator (`Zp`) or unpaired surrogate (`Cs`) code point. A
  supplementary character encoded as a valid surrogate pair is one code point
  of its own category and is allowed.
- More than **255 code points**. OpenID Connect Core limits `sub` to 255 ASCII
  characters, an e-mail address is at most 254 (RFC 5321), and directory user
  principal names are shorter still. The column is `TEXT`, so storage sets no
  bound; the identifiers a session can present do.

Unassigned (`Cn`) and private-use (`Co`) code points are not refused: a newer
Unicode version may assign them, and refusing what a provider can legitimately
emit would lock a person out.

### Refuse, never trim

A trimmed grant would be a different string from the one the administrator
typed, and the response would not say so. Refusing keeps the grant, the ledger
row and the matched value identical.

### 422, not 400

The task asked for 400. The endpoint already answers 422 for every refusal of
the request body's content (missing principal, unknown role, scope mismatch),
and the API reference documents that. A malformed principal is the same class
of refusal, so it joins 422; adding 400 would give one field two statuses for
the same kind of fault. The refusal message names the rule broken, never the
value, so nothing hostile is echoed.

### The estate validates before it looks for an existing grant

`reconcileGrant` applies the rule first. Otherwise a stored row carrying the
malformed principal would make the declaration "unchanged" and it would never
be reported. The failure entry's name and its ledger detail render the
principal with control, format and separator code points escaped as `\uXXXX`
and cut at the length bound, because the ledger is append-only and the value is
known to be malformed.

### Claim values: no trim, lone string kept

Token values are compared exactly. The lone string is kept as a one-element
list, as today: providers emit a single-valued claim as a string (a group
mapper configured single-valued, a federation service with one group), and
`SVC_GW_AUTH_0015.1` — Claim-derived roles compose, scope correctly, and never
widen past an exact match — already relies on it. It still must match exactly, and a
delimited string is still never split. Any other type (number, boolean, object,
null) grants nothing, as today; the randomized sweep keeps proving it.

A configured `claim-value` with surrounding whitespace or the characters above
now refuses startup rather than being trimmed. YAML drops unquoted surrounding
whitespace itself, so this only fires for a value the operator quoted with the
padding inside it, which can only be a mistake.

### Existing tests that asserted trimming

`ClaimRoleMappingTests.claim_values_that_only_resemble_a_mapping_grant_nothing`
asserted a padded value is granted, and the sweep's oracle in
`ClaimRoleMapperTests` trimmed. Both now expect the exact behaviour. This
tightens them: `SVC_GW_AUTH_0015.1` — Claim-derived roles compose, scope
correctly, and never widen past an exact match — lists whitespace near-misses among the
values that confer nothing.

## Failure model (Tier 3, authorization)

| Way this can hurt | Layer that catches it |
| --- | --- |
| A write path that skips the rule | estate test with a pre-existing malformed row; the API test; `RoleGrantRepository.insert` has one caller |
| Refusing a legitimate principal (e-mail, UUID `sub`, non-ASCII name, astral character, exactly 255) | positive cases in the API test and the randomized rule sweep |
| A surrogate pair mis-read as two unpaired surrogates, or the reverse | rule sweep against an independent oracle; explicit astral and lone-surrogate cases |
| The refused value echoed into a response, log or ledger | API test asserts the response body omits it; estate test asserts the escaped rendering on the ledger |
| Trimming coming back in the matcher | the mapper sweep's oracle compares untrimmed; HTTP test with padded values |
| A padded configured value silently trimmed | startup refusal test |

## Risks / Trade-offs

- [A provider that emits padded group names loses the derived role] → it never
  matched a configured value exactly; the configuration reference says so.
- [A principal over 255 code points cannot be granted] → no OIDC `sub` or
  e-mail address is that long; `roles.admins` and claim mappings remain.

## Migration Plan

None. Pre-1.0, no stored rows to repair. A database that already holds a
malformed grant keeps it listable and revocable by id.

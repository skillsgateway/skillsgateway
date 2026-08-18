# Policy deny rules

A policy rule is an organizational prohibition as data: a named
[CEL](https://cel.dev) expression over a snapshot's facts, evaluated at the
moment an approval is requested. If any enabled rule **matches**, the approval
is refused; the snapshot stays held and nothing is published. Rules tighten
the human approval gate — they never open it: there is no auto-approval, and a
policy denial has no per-snapshot override.

Rules are managed through the [policy API](../reference/api/policy.md)
(admin-only mutations, auditor-or-admin reads) or declared in the
[estate configuration](../reference/configuration.md#declarative-estate). An
expression is compiled — parsed and type-checked to a boolean — when the rule
is written, so a broken expression is the operator's error at edit time, never
a surprise at review time.

## The evaluation variables

Every expression evaluates over four variables, built from exactly the pinned
commit the snapshot identifies:

| Variable | Shape | Meaning |
| --- | --- | --- |
| `snapshot` | map | `id` (int), `sha` (string), `marketplace` (string), `state` (string: `held`, `approved`, `rejected`, `revoked`). |
| `files` | list of maps | Every path in the pinned tree: `path` (string), `size` (int). |
| `plugins` | list of maps | The manifest's plugins: `name`, `description`, `source` (strings; absent manifest fields are `""`). |
| `skills` | list of maps | Every skill (a `SKILL.md` under a plugin's `skills/` tree): `name`, `path`, `plugin` (strings), `tools` (list of strings). |

`skills[].tools` is parsed from the SKILL.md YAML frontmatter key
`allowed-tools`, accepting both the YAML-list and the comma-separated-string
form (`Bash(git add:*, git commit:*), Read` splits on top-level commas only).
No frontmatter, or frontmatter without `allowed-tools`, is honestly an empty
list. **Malformed** frontmatter is not: tools that cannot be read must never
read as "no tools", so it fails the facts and denies the approval (see
fail-closed below).

## Examples

Deny skills declaring shell tools — the issue's flagship rule:

```cel
skills.exists(s, s.tools.exists(t, t.startsWith("Bash")))
```

Scope a rule to one marketplace (rules are global; the expression scopes):

```cel
snapshot.marketplace == "corp-marketplace"
  && skills.exists(s, s.tools.exists(t, t.startsWith("Bash")))
```

Deny binaries by extension, or anything oversized:

```cel
files.exists(f, f.path.endsWith(".exe") || f.size > 10 * 1024 * 1024)
```

Deny a plugin namespace:

```cel
plugins.exists(p, p.name.startsWith("internal-"))
```

## Fail-closed, by design

Anything that prevents a rule from producing `false` **denies** the approval:

- the expression evaluates `true` (*matched*);
- the expression errors at evaluation (a missing map key, an out-of-range
  index, an exceeded evaluation bound);
- the facts cannot be built — malformed SKILL.md frontmatter, a SKILL.md over
  256 KiB, a file inventory over 20 000 entries.

The refusal (HTTP 409 on `POST /api/snapshots/{id}/approve`) names **every**
deciding rule with its outcome — `matched`, or `error: …` — because the remedy
differs: a match means the content is prohibited; an error means an admin
fixes or disables the rule. The deliberate cost: a broken enabled rule blocks
approvals until it is fixed or disabled. That is the point — an attacker who
could provoke an evaluation error must not thereby switch the rule off.

Evaluation is bounded (a comprehension-iteration cap and the inventory caps
above), so a hostile expression or hostile content errors out instead of
hanging the gate.

!!! warning "No waivers for policy denials"

    A [vetting waiver](waiving-findings.md) accepts one finding, on one
    marketplace, with an expiry. A policy denial has no such per-snapshot
    exception on purpose: the rule *is* the decision, so the exception path is
    an admin editing or disabling the rule — audited on the ledger — or the
    content changing upstream and being re-ingested.

## Test before you enforce: the playground

`POST /api/policy/playground` evaluates any expression against a **real**
snapshot — held, approved or revoked — and answers `matched` or the error,
without storing anything, appending nothing to the ledger, and changing no
state. Author the rule in the playground against the snapshots it should and
should not catch, then create it enabled.

```console
$ curl -X POST localhost:8080/api/policy/playground \
    -H 'Content-Type: application/json' \
    -d '{"snapshotId": 42,
         "expression": "skills.exists(s, s.tools.exists(t, t.startsWith(\"Bash\")))"}'
{"matched":true}
```

The playground requires permission to approve the named snapshot: it
evaluates quarantine-backed facts, so it is scoped exactly like the approval
it rehearses.

## What lands in the ledger

| Event | Written when | Detail carries |
| --- | --- | --- |
| `policy-rule-created` | a rule is created (API or estate) | `rule=<name> enabled=<bool>` |
| `policy-rule-updated` | a rule is updated | `rule=<name> enabled=<bool>` |
| `policy-rule-deleted` | a rule is deleted | `rule=<name>` |
| `policy-denied` | a rule decides against an approval — one entry per deciding rule | `rule=<name> outcome=matched` or `outcome=error: …`, with the snapshot SHA, the marketplace, and the refused reviewer as the acting identity |

An auditor asking "why was this approval refused, and by which rule" answers
it from the ledger alone. A successful approval records nothing new here: a
rule that did not match did not decide, and `snapshot-approved` already
carries the decision that was made.

## Declaring rules as configuration

Rules join the [declarative estate](declarative-estate.md) under
`skills-gateway.estate.policy-rules` — created or converged through the same
compiled, audited path as the API, additively (an undeclared rule is never
deleted) and idempotently (an identical rule reconciles with zero writes). A
declared expression that does not compile is an isolated entry failure: loud
in the log, on the ledger and in the reconciliation report, and never a
stored rule.

```yaml
skills-gateway:
  estate:
    policy-rules:
      - name: no-shell-tools
        description: deny skills declaring shell tools
        expression: 'skills.exists(s, s.tools.exists(t, t.startsWith("Bash")))'
        # enabled defaults to true: a declared rule is declared to enforce
```

## Reading further

- [Policy API](../reference/api/policy.md) — the endpoints.
- [Approving and rejecting snapshots](approving-snapshots.md) — the gate the
  rules tighten.
- [Vetting](../concepts/vetting.md) — evidence about content; policy rules are
  standing decisions about it. The two gates compose: vetting first, policy
  second, both before any state transition.
- [Trust boundaries](../concepts/trust-boundaries.md) — why only
  `ApprovalService` publishes.
- ADR 0006 in [Architecture decisions](../reference/decisions.md) — why
  embedded CEL and why deny-only.

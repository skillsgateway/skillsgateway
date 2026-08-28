# Policy

CEL deny rules and their playground (GW_0089–GW_0092). What the rules mean,
the evaluation variables, and the fail-closed semantics are in
[Policy deny rules](../../guides/policy-rules.md); the declarative form is in
[Configuration](../configuration.md#declarative-estate).

Under role enforcement, rule mutations are **admin-only**, the listing is
**auditor-or-admin**, and the playground requires permission to **approve the
named snapshot** (it evaluates quarantine-backed facts, so it is scoped like
the approval it rehearses).

**Machine reach.** `policy:read` covers `GET /policy/rules` **and** `POST
/policy/playground` — a `POST` inside a read scope, because the playground
evaluates a policy and persists nothing. `policy:write` covers rule creation,
update and deletion, and does **not** confer `policy:read`: no scope implies
another. See [Machine API credentials](tokens.md#machine-api-credentials).

---

## `POST /api/policy/rules`

Create a rule. The expression is compiled — parsed and type-checked to a
boolean over the policy variables — before anything is stored.

```console
$ curl -X POST localhost:8080/api/policy/rules \
    -H 'Content-Type: application/json' \
    -d '{"name": "no-shell-tools",
         "description": "deny skills declaring shell tools",
         "expression": "skills.exists(s, s.tools.exists(t, t.startsWith(\"Bash\")))"}'
```

```json
{"id":1,"name":"no-shell-tools","description":"deny skills declaring shell tools",
 "expression":"skills.exists(s, s.tools.exists(t, t.startsWith(\"Bash\")))",
 "enabled":true,"createdBy":"alice","createdAt":"2026-08-18T12:00:00Z",
 "updatedBy":null,"updatedAt":null}
```

`enabled` may be omitted and defaults to `true`. An enabled rule is in force
at the very next approval — there is no propagation delay to wait out.

| Status | Meaning |
| --- | --- |
| 200 | Rule created; the ledger records `policy-rule-created`. |
| 403 | Enforcement is enabled and the caller is not an admin. |
| 409 | A rule of that name exists. |
| 422 | Malformed name, or an expression that does not compile to a boolean. |

## `GET /api/policy/rules`

Every stored rule, enabled or not.

| Status | Meaning |
| --- | --- |
| 200 | All rules. |
| 403 | Enforcement is enabled and the caller holds neither auditor nor admin. |

## `PUT /api/policy/rules/{name}`

Replace a rule's description, expression and enabled flag. The new expression
is compiled first; on 422 the stored rule is unchanged. Disabling a rule is
the audited off-switch — there is no per-snapshot waiver of a policy denial.

| Status | Meaning |
| --- | --- |
| 200 | Rule updated; the ledger records `policy-rule-updated`. |
| 403 | Enforcement is enabled and the caller is not an admin. |
| 404 | No rule of that name. |
| 422 | An expression that does not compile. |

## `DELETE /api/policy/rules/{name}`

Remove the rule. Past denials it decided stay on the append-only ledger.

| Status | Meaning |
| --- | --- |
| 200 | Rule deleted; the ledger records `policy-rule-deleted`. |
| 403 | Enforcement is enabled and the caller is not an admin. |
| 404 | No rule of that name. |

---

## `POST /api/policy/playground`

Evaluate any expression against a real snapshot without enforcing, storing or
recording anything. Errors are answers, not failures: a broken expression
returns 200 with the error, so authoring is an edit-evaluate loop.

```console
$ curl -X POST localhost:8080/api/policy/playground \
    -H 'Content-Type: application/json' \
    -d '{"snapshotId": 42, "expression": "files.exists(f, f.path.endsWith(\".exe\"))"}'
{"matched":false}
```

| Field | Meaning |
| --- | --- |
| `matched` | Whether the expression matched; absent when it errored. |
| `error` | The compile or evaluation error; absent when the expression answered. Never snapshot content. |

| Status | Meaning |
| --- | --- |
| 200 | The expression's answer or its error. Nothing was persisted or recorded. |
| 403 | Enforcement is enabled and the caller may not approve that snapshot. |
| 404 | No such snapshot. |

---

## How a denial looks

Policy is enforced by `POST /api/snapshots/{id}/approve`
([Marketplaces and snapshots](marketplaces.md)): after the vetting gate and
before any state transition, every enabled rule is evaluated; any match or
error refuses with 409 naming all deciding rules, and one `policy-denied`
ledger entry per deciding rule:

```json
{"type":"about:blank","title":"Policy rules denied this snapshot","status":409,
 "detail":"snapshot 42 cannot be approved: policy rules [no-shell-tools] denied it. ...",
 "denials":[{"rule":"no-shell-tools","outcome":"matched"}]}
```

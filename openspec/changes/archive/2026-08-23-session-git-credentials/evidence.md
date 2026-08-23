# Evidence: session-git-credentials

One final fresh run of every gate after the last code edit, against a clean
tree at `226d3dea56d5cb8005da443c440cb011ad35a744`. Edits after it are this
report, the tasks checklist, and the archive move — no source changes.

## Discipline notes (old-coder Tier 3 — this issues a facade credential)

- **Spec approval: not obtained (autonomous run).** The committed OpenSpec
  change plus **ADR 0008** are the spec the implementation was held to, offered
  for review after the fact. Confidence is claimed correspondingly lower.
- **RED observed before GREEN.** `SessionCredentialTests` was written against a
  `createSessionCredential(...)` stub that threw; all five cases failed on that
  behaviour rather than on compilation before the implementation existed
  (`Tests run: 5, Failures: 0, Errors: 5` — every error the
  `UnsupportedOperationException`). This is the full loop, unlike the two
  changes before it.
- **A test caught a real bug.** `rotate` was dropping `session_derived`, so a
  rotation would have laundered a session credential into a standing one —
  exactly failure mode F4. Caught by
  `rotation_keeps_the_deadline_and_the_mark` before any of this was committed.
- **The route-table guard fired and was honoured.**
  `RoleEnforcementTests.ROLE_GATED_MUTATIONS` is asserted equal to the running
  application's own route table, so `POST /api/tokens/session` had to be
  classified deliberately. It is owner-scoped, beside the other token routes:
  it grants the session's own principal exactly what that principal could
  already ask for, with a shorter life and no publication authority.
- **`AuthTests`' closed field enumeration** required `sessionDerived` to be
  added deliberately — a fact about the grant, never a secret.
- **Scope reduced deliberately.** ADR 0008's other half, the read-only forge
  mirror for browsing, is **not implemented** and is sequenced after this. It
  is an outbound integration with its own failure modes (partial pushes, a
  mirror drifting from what is served, revocation that must reach it), and it
  must never become a surface people install from.

## Failure model coverage

All seven modes in `design.md` have a test. F6 (works without a session) is
carried by the existing web-chain behaviour — the endpoint is on the OIDC-only
chain, and `AuthTests` already asserts `/api/**` answers 401 unauthenticated.

## Mutation pass

Seven mutants against the issuing path, applied one at a time and restored via
`git checkout --` (verified after each; `grep -rn MUTANT src/` clean before this
report). The runner fails closed on a non-unique anchor, a survivor, or a dirty
tree — and did fail closed twice on stale anchors after a `spotless:apply`
reformatted the lines, which is the behaviour wanted.

| # | Mutant | Result |
| --- | --- | --- |
| 1 | The endpoint honours the caller's requested `expiresAt` after all | KILLED |
| 2 | The credential is not marked session-derived | KILLED |
| 3 | A session credential is granted a push scope | KILLED |
| 4 | Scope narrowing is dropped, granting every marketplace | KILLED |
| 5 | Rotation drops the session-derived mark | KILLED |
| 6 | No expiry is granted at all | KILLED |
| 7 | The configured session TTL is ignored for a fixed one | KILLED |

**Negative control.** An eighth "mutant" changing only a javadoc comment
**SURVIVED**, as required.

## Adversarial pass

- Posting `expiresAt: 2099-01-01` to the session endpoint — granted expiry is
  still the configured TTL, asserted to be before 2098.
- A session credential attempting a real `git push` to a hosted marketplace —
  refused.
- A session credential narrowed to one marketplace attempting another —
  refused, by the same scope path as any token.
- Rotating a session credential — deadline and mark both survive, so it cannot
  be laundered or extended.
- A credential from a gateway configured with a 1ms session TTL — fails
  authentication with no scheduler having run, and its facade clone fails,
  confirming expiry is a comparison at use (GW_0065) and not a sweep.

## Known limits (declared, not covered)

- **No forge mirror** — deliberately out of scope, see above.
- **Not tied to session lifetime.** The credential dies on its timer, not when
  the browser session ends; the gateway does not track session lifetime. This
  is stated in the guide and in the endpoint's own API description rather than
  implied away, and it is the honest limit of the mitigation.
- **No portal button.** The endpoint is what a "get a git credential" control
  will call; the UI is a self-contained follow-up, as for the two changes
  before it.
- **No credential helper or device-code flow.** A non-interactive client-side
  convenience over the same endpoint, not part of this.
- **Eight hours is a guess about a working day.** It is a property precisely so
  a deployment that disagrees can say so; no test asserts the number is *right*,
  only that the configured one is what is granted.
- **A short-lived credential still leaks like any bearer token** for the length
  of its life. What it buys is a bounded window and an attributable origin.

## Gates

### `./mvnw clean verify`

```
[INFO] Tests run: 176, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:18 min
```

### `(cd src/main/frontend && pnpm test:stories)`

```
      Tests  6 passed (6)
```

### `(cd src/main/frontend && pnpm e2e)`

```
  12 passed (27.4s)
```

### `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
101/101 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 26 passed, 0 failed (26 items)
```

### `mkdocs build --strict`

```
INFO    -  Documentation built in 0.85 seconds
```

## Commit

Final implementation commit: `226d3dea56d5cb8005da443c440cb011ad35a744`
(every gate above ran against this tree; the archive commit follows).

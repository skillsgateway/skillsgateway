# Evidence: read-only-forge-mirror

Trust boundary: this hangs off `ApprovalService` and the revocation path and
creates the gateway's second outbound network surface, so the old-coder
discipline applies. This report records what was executed, on what, and what was
deliberately not.

**Commit under test:** `4662478ebc99bc4ae9d656569940dfe01db6db0b`
(`docs(mirror): document the read-only forge mirror`), rebased onto
`312b750` — the tip of `main` at the time of the run.

All six gates below were run **fresh, in order, after the last code edit**.

## Spec ↔ test mapping

| Requirement | What is proved | Test(s) |
| --- | --- | --- |
| GW_FACADE_0020 — Read-only forge mirror of approved content | the shipped default pushes nothing; an enabled mirror comes to hold exactly the served references; a snapshot that only ever reached quarantine never appears; the credential is in no ledger entry or API response; the URL faces registration's scheme allowlist and may not embed a credential | `ForgeMirrorDisabledTests` (SVC_GW_FACADE_0020), `ForgeMirrorTests` (SVC_GW_FACADE_0020), `MirrorUrlPolicyTests` (3 cases, SVC_GW_FACADE_0020) |
| GW_FACADE_0021 — The mirror is never an enforcement path | with the mirror pointed at something that cannot be pushed to, the approval returns the approved snapshot, a **real `git clone` through the facade** gets the approved commit, and the failure is only visible in the report and the ledger | `ForgeMirrorFailureTests` (SVC_GW_FACADE_0021) |
| GW_FACADE_0022 — Revocation propagates to the mirror | a reachable mirror stops holding the revoked snapshot's references; an unreachable one leaves the revocation, the snapshot's `revoked` state and the facade's refusal to serve it untouched, and the divergence is recorded | `ForgeMirrorTests` (SVC_GW_FACADE_0022), `ForgeMirrorFailureTests` (SVC_GW_FACADE_0022) |
| GW_FACADE_0023 — Mirror drift is reportable | the report agrees before an alteration and names the missing and no-longer-served references after it; an unreachable mirror reports `reachable=false, inSync=false`; the endpoint is refused to a non-administrator | `ForgeMirrorTests` (2 cases, SVC_GW_FACADE_0023), `ForgeMirrorFailureTests` (SVC_GW_FACADE_0023), `RoleEnforcementTests` (privileged-read walk) |

## What the tests actually push to

`ForgeMirrorTests` and `ForgeMirrorFailureTests` run against a **bare repository
on disk over `file://`**, through the real JGit transports — `Transport.push`
with `RemoteRefUpdate`s, and `LsRemoteCommand` for the read side. Nothing is
mocked and **no test reaches the network**. `ForgeMirrorTests` reads its
assertions back out of the bare repository rather than out of the gateway's own
opinion of what it pushed.

## The adversarial cases (the guardrails, not the happy path)

- **Quarantine is not mirrored, directly rather than by implication.** A second
  snapshot is ingested and left `held`; the mirror is then asserted not to carry
  its SHA. It is not enough that approval happens to be the trigger — a
  reconciliation that read the wrong repository would have pushed it anyway.
- **A broken mirror is asserted against everything except the mirror.**
  `ForgeMirrorFailureTests` checks the approval's return value, a real `git
  clone` before the revocation, the `revoked` row, `publishedIfServing` being
  empty afterwards, and the clone then failing. If the mirror were an enforcement
  path, that is where it would show.
- **Drift is seeded behind the gateway's back.** A served reference is deleted
  from the bare repository and one the gateway never published is added, so the
  report is proved to compare against the mirror's actual references rather than
  against push history. The subsequent revocation then repairs both, which is
  the reconcile-not-delta property demonstrated rather than asserted.
- **An unreachable mirror is never "in sync".** `reachable=false` forces
  `inSync=false`; silence is not agreement.
- **The credential is configured in the passing suite** (`mirror-bot` /
  `mirror-secret`) precisely so that "the report and the ledger do not contain
  it" means something.
- **A near-miss URL is not mistaken for a credential.**
  `https://forge.example.com/~alice@corp/mirror.git` is accepted while
  `https://bot:s3cret@forge.example.com/corp.git` is refused.
- **The scheme allowlist is the whole check.** `file:///srv/mirror.git` is
  refused under the default `http,https` allowlist and accepted only when an
  operator has widened it — which is exactly the mechanism the suites rely on.

## Honest deviations

- **The tests were written after the code they cover, not proved red first.**
  Two of them did fail before they passed, and both failures were real: the drift
  report counted the mirror's own `HEAD` as drift (fixed by filtering both sides
  through `GitStorage.isServedRef`), and a fixed mirrored-marketplace name met
  the previous run's published references because the data directory outlives a
  run (fixed by generating the name per JVM).
- **`RoleEnforcementTests` was modified, and only to make it stricter**: `GET
  /api/mirror/drift` was added to the privileged-read walk, so a no-role session
  and an auditor are now both asserted to be refused it. No existing assertion
  was weakened or removed.

## Gate results

All commands run from the repository root at
`4662478ebc99bc4ae9d656569940dfe01db6db0b`.

### 1. `./mvnw clean verify`

```
[INFO] Tests run: 503, Failures: 0, Errors: 0, Skipped: 0
[INFO] Spotless.Java is keeping 286 files clean - 0 needs changes to be clean
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  04:44 min
[INFO] Finished at: 2026-09-07T07:11:13+02:00
```

### 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
   Start at  07:11:29
   Duration  3.52s
```

### 3. `(cd src/main/frontend && pnpm e2e)`

```
  ✓  13 [chromium] › e2e/portal.spec.ts:665:1 › the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim (413ms)

  13 passed (45.0s)
```

### 4. `reqstool status local -p docs/reqstool`

```
  GW_FACADE_0020             skills-gateway
  GW_FACADE_0021             skills-gateway
  GW_FACADE_0022             skills-gateway
  GW_FACADE_0023             skills-gateway

INCOMPLETE (0)
159/159 complete · 0 incomplete · PASS
```

### 5. `openspec validate --all --strict`

```
✓ change/read-only-forge-mirror
Totals: 31 passed, 0 failed (31 items)
```

### 6. `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 1.22 seconds
```

**No flake reruns were needed on this run.** Neither of the two known ones —
`AuditExportTests`' `ConcurrentModificationException` and the vitest/Storybook
runner wedge — appeared.

## Not covered, and why

- **A real forge.** Every test pushes to a local bare repository. What is
  verified is the gateway's behaviour over the git transport, not any particular
  host's authentication or API — which this increment deliberately does not use.
- **A restart with work queued.** The queue is in memory and losing it is an
  accepted, documented consequence (see `design.md`, risks); a reconciliation is
  idempotent and the drift report makes the staleness visible, so there is no
  hidden state to test.
- **Concurrent reconciliations.** They cannot happen: the executor is
  single-threaded, and that is the property rather than a race to test.

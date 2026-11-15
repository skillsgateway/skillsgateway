# SPEC — marketplace-auto-approval

- Tier: **3**. The change lets content reach the served repository with no
  person deciding. It changes the approval trust boundary (ADR 0006's
  "nothing is served that a person did not approve"), the approval evidence,
  and recall.
- What to build is the OpenSpec change beside this file (proposal.md,
  design.md D1–D14, tasks.md). This SPEC says how the implementation earns
  trust: the failure model, the scenarios each test proves, what must not
  change, and the setup.
- Spec approval: **approved** by the owner on 2026-11-15, with revisions R1–R3.
- Setup plan:
  - Tools to install: none. Coverage and mutation tools are not in the build;
    mutation is the repo's established manual runner (below).
  - Isolation: branch `feat/marketplace-auto-approval` in the main checkout.
    Not a worktree: the gauntlet needs the built `node_modules`, the Playwright
    browsers and Podman with `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`, none of
    which a fresh worktree has.
  - Git: signed-off commits with `Assisted-by: Claude:claude-opus-5-5` and no
    tool `Co-authored-by`. This SPEC is committed at approval. Then one
    checkpoint commit per task group (1 to 9), each green. Every new test is
    watched failing before its implementation; the failing test name and its
    assertion line are recorded in `evidence.md`. `evidence.md` is the last
    commit before `/opsx:archive`.
  - Files the gauntlet adds, by path:
    - `openspec/changes/marketplace-auto-approval/mutants.sh`, the manual
      mutation runner, in the shape of the archived ones (fails closed: a
      mutant that does not apply exactly once, a missing test report, the
      named test not failing, or a dirty `src/main` after restore stops the
      run);
    - `openspec/changes/marketplace-auto-approval/evidence.md`.
  - New dependencies: none. Container-backed tests use the existing Arconia
    dev services (PostgreSQL, floci); no raw Testcontainers container.
  - Upstreams in tests are in-process `GitHttpFixture`s on loopback; no test
    reaches the internet.

## Failure model

| # | How this change could hurt | What catches it |
|---|---|---|
| F1 | External content is published without a person: a closure member slips through because its URL looks internal, or members are counted after grafting | S6.1, S6.2, E2 |
| F2 | "Internal" matches too much: lookalike organisation, other scheme, host or port, userinfo, encoded separators, dot segments, case-shifted path | S2.2–S2.6, startup refusals S2.7–S2.9 |
| F3 | A vetter is switched off and the one that remains passes, so the chain "clears" on less evidence | S7.3, S7.7 |
| F4 | A stale or missing chain reads as current (`UNDETERMINED` treated as not stale) | S7.1 |
| F5 | A waiver created for new content pre-clears it | S7.5 |
| F6 | An unread surface reads as unchanged: oversize file, past `MAX_PLUGINS`, parse error, or a fail-open `null` | S8.7, S8.8, S7.6 |
| F7 | A new executable path counts as documentation: unknown extension, `100755`, mode-only change, symlink, gitlink, shebang, dependency manifest, launch target | S8.3–S8.6 |
| F8 | The served tip moves backwards: an older held snapshot is approved after a newer one exists | S5.1 |
| F9 | A revoked or deleted snapshot is approved again without the second-administrator reversal | S5.2 |
| F10 | An automatic approval bypasses a gate: policy, name collision, open closure, release age, four-eyes | S9.1–S9.4 |
| F11 | A race with a person publishes twice or records the wrong decider | S9.5 |
| F12 | The automatic approval leaves out evidence a human approval records, or the refactor changes the human approval's evidence | S10.1, S10.2 (pinned before the move) |
| F13 | The ledger is flooded with identical skips on every tick, or a skip is silently not recorded | S11.2, S11.3 |
| F14 | `shadow` or `off` publishes anyway | S12.1, S12.2 |
| F15 | Auto-approved content is never re-checked: re-vetting disabled, or `warn` mode only records | S12.4, S13.1 |
| F16 | Re-vetting mass-withdraws human-approved content | S13.2 |
| F17 | Bulk withdrawal touches human-approved snapshots, or anything before `since` | S14.2, S14.3 |
| F18 | One administrator grants `auto` alone under four-eyes `enforce`, or the reconciler exemption leaks into approvals | S4.2, S4.4 |
| F19 | A lapsed posture or override still takes effect | S1.3, S3.3 |
| F20 | A non-administrator sets a posture, an override or a withdrawal | S1.5, S14.1 |
| F21 | The estate declares an override, or `auto` with no basis or too distant an expiry | ES.2–ES.4 |
| F22 | Defaults change today's behaviour (posture `manual`, mode `off`, `required-vetters` unset) | S1.1, S7.2, S12.1, the existing approval suite unchanged |
| F23 | `required-vetters` silently requires nothing: an empty list, a misspelt name, or a required vetter removed from the chain passing vacuously | S7.9, S7.10 |
| F24 | A relaxed `required-vetters` hides which checks did not run | S7.8 |
| F25 | The portal shows a lapsed posture or override as active, shows `auto` under mode `off` as publishing, or offers editing to a non-administrator | S15.1–S15.3 |
| F26 | The portal enforces a limit of its own that disagrees with the server, or swallows a refusal | S15.4 |

## Scenarios

Each scenario is one test method, named after it in snake case. `S<n>.<m>`
belongs to `SVC_GW_APPROVAL_0044.<n>`; `E` to the parent
`SVC_GW_APPROVAL_0044`; `ES` to `SVC_GW_ESTATE_0006`. The `S15` portal
scenarios are Storybook story tests, except S15.5, which is the e2e test that
carries `@SVCs SVC_GW_APPROVAL_0044.15`.

```gherkin
Feature: an administrator can opt an internal marketplace into automatic approval

  # --- 0044.1 posture ---
  Scenario: S1.1 a registered marketplace is manual
  Scenario: S1.2 auto needs a reason and an expiry of at most 90 days
    When  auto is set with no reason, no expiry, or an expiry 91 days out
    Then  400, and the posture stays manual
  Scenario: S1.3 a lapsed auto posture is effectively manual
    Given auto set with an expiry, and the clock moved past it
    Then  the basis is none and a clean snapshot stays held
  Scenario: S1.4 auto can be chosen at registration by an administrator
  Scenario: S1.5 a non-administrator cannot set a posture
    Then  403, on PUT and on registration with an approval field
  Scenario: S1.6 auto at registration without an internal basis is refused
    Then  409 with nextStep naming the override, and no marketplace is created

  # --- 0044.2 internal sources ---
  Scenario: S2.1 a URL under an organisation prefix is internal, and a hosted marketplace is internal
  Scenario: S2.2 a lookalike organisation is not internal ("acme" vs "acme-evil")
  Scenario: S2.3 another scheme, host or port is not internal
  Scenario: S2.4 userinfo in the URL is not internal
  Scenario: S2.5 encoded separators and dot segments are not internal ("%2f", "%2e%2e", "/acme/../evil")
  Scenario: S2.6 a case-shifted path segment is not internal ("/ACME/")
  Scenario: S2.7 startup refuses an unparseable prefix
  Scenario: S2.8 startup refuses a host-wide prefix on github.com, gitlab.com and bitbucket.org
  Scenario: S2.9 startup refuses a host-wide prefix on the github-base-url host

  # --- 0044.3 override ---
  Scenario: S3.1 auto outside the prefixes needs an override with a reason and at most 90 days
  Scenario: S3.2 the override caps the posture's expiry
  Scenario: S3.3 an expired or revoked override makes the basis none, recorded once
    Then  exactly one auto-approval-override-expired (or -revoked) entry across repeated attempts

  # --- 0044.4 four-eyes on the grant ---
  Scenario: S4.1 under warn, a grant by the registering administrator is set and recorded as a conflict
  Scenario: S4.2 under enforce, it stays awaiting-second-administrator until a different administrator confirms
  Scenario: S4.3 the estate reconciler's grant needs no second administrator
  Scenario: S4.4 approvals' four-eyes behaviour is unchanged (config-reconciler still conflicts on approve)

  # --- 0044.5 candidacy ---
  Scenario: S5.1 an older held snapshot attempted after a newer one exists stays held
  Scenario: S5.2 a revoked or deleted snapshot is never a candidate

  # --- 0044.6 no external source ---
  Scenario: S6.1 one closure member with an external URL keeps the snapshot held
  Scenario: S6.2 one closure member with an internal URL keeps the snapshot held

  # --- 0044.7 vetting ---
  Scenario: S7.1 an UNDETERMINED or SUPERSEDED chain keeps it held
  Scenario: S7.2 with required-vetters unset, a DISABLED, NOT_REACHED, ERROR or PENDING verdict keeps it held
  Scenario: S7.3 with required-vetters unset, every vetter but secret-scan disabled, secret-scan passing, keeps it held
  Scenario: S7.4 an uncovered high finding keeps it held; medium findings only are approved
  Scenario: S7.5 a waived high finding absent from the baseline in that blob keeps it held; present in it, approved
  Scenario: S7.6 hook-config-unreadable, hook-target-unscanned or too-many-plugin-roots at any severity keeps it held
  Scenario: S7.7 with required-vetters [secret-scan], secret-scan disabled keeps it held; a NOT_REACHED, ERROR or PENDING verdict from any vetter keeps it held
  Scenario: S7.8 with required-vetters [secret-scan], another vetter disabled and secret-scan passing is approved, and snapshot-auto-approved names the disabled vetter
  Scenario: S7.9 startup refuses an empty required-vetters list and a name that is not a configured vetter
  Scenario: S7.10 a required vetter absent from the chain keeps every snapshot held

  # --- 0044.8 executable surface ---
  Scenario: S8.1 a Markdown-only change and a deletion are approved
  Scenario: S8.2 a first snapshot that is entirely inert is approved; one with any surface is held
  Scenario: S8.3 a changed hook, hook module, MCP or LSP declaration keeps it held
  Scenario: S8.4 changed allowed-tools frontmatter or agent tools keeps it held
  Scenario: S8.5 an added path with an unknown extension, a shebang, a dependency manifest or a launch target keeps it held
  Scenario: S8.6 mode 100755, a mode-only change, a symlink or a gitlink keeps it held
  Scenario: S8.7 an oversize component file keeps it held
  Scenario: S8.8 plugins past MAX_PLUGINS keep it held

  # --- 0044.9 gates ---
  Scenario: S9.1 a policy denial keeps it held
  Scenario: S9.2 a name collision keeps it held
  Scenario: S9.3 an open closure keeps it held
  Scenario: S9.4 a too-young snapshot is held, and approved on a later re-vetting tick once of age
  Scenario: S9.5 when a person decides first, the automatic attempt records a skip and publishes nothing

  # --- 0044.10 evidence ---
  Scenario: S10.1 a human approval writes exactly its pinned ledger entries and webhook (before and after the refactor)
  Scenario: S10.2 an automatic approval writes the same entries and webhook, through ApprovalRecorder

  # --- 0044.11 ledger ---
  Scenario: S11.1 snapshot-auto-approved is typed system with basis, grantor, chain, baseline SHA, worst severity and waiver ids
  Scenario: S11.2 a skip is ledgered when its reason changes, and not again when it repeats
  Scenario: S11.3 the skip reason is stored on the snapshot

  # --- 0044.12 mode ---
  Scenario: S12.1 under off, nothing is evaluated for publication
  Scenario: S12.2 under shadow, would-approve is recorded and nothing is published
  Scenario: S12.3 under on, the snapshot is published
  Scenario: S12.4 with revet.enabled=false, the attempt skips with revet-disabled

  # --- 0044.13 re-vetting ---
  Scenario: S13.1 under global warn, a violation revokes and unpublishes an auto-approved snapshot
  Scenario: S13.2 the same violation on a human-approved snapshot only records

  # --- 0044.14 bulk withdrawal ---
  Scenario: S14.1 withdrawal is admin-only (403 otherwise)
  Scenario: S14.2 it revokes exactly the live auto-approved snapshots since `since`, newest first, and lists them
  Scenario: S14.3 human-approved snapshots, and auto-approved ones before `since`, are untouched

  # --- parent, end to end (AutoApprovalTests) ---
  Scenario: E1 an internal marketplace in on mode ingests a Markdown-only update and serves it with no person
  Scenario: E2 the same update with an external source added stays held

  # --- 0044.15 portal (stories, and one e2e) ---
  Scenario: S15.1 the card shows posture, basis, grantor, reason, expiry and override for manual, internal, override and awaiting-second-administrator states
  Scenario: S15.2 a lapsed posture or override reads as lapsed, and auto under mode off or shadow reads as not publishing
  Scenario: S15.3 a non-administrator sees the card with no edit or revoke controls
  Scenario: S15.4 a refused save shows the server's message and nextStep, and the date picker allows at most 90 days
  Scenario: S15.5 e2e: an administrator sets auto with an override outside the prefixes, sees basis override, revokes it, sees basis none

  # --- GW_ESTATE_0006 ---
  Scenario: ES.1 a declared auto posture is applied as config-reconciler
  Scenario: ES.2 declared auto with no internal basis is a reconciliation failure
  Scenario: ES.3 declared auto with a missing or too-distant expiry is a reconciliation failure
  Scenario: ES.4 the estate cannot declare an override
```

Tasks 4.4 (the posture endpoints) and 5.9 (the triggers) carry no SVC of
their own. They are covered through the scenarios that drive them: S1.x and
S3.x go through the endpoints, and E1, S9.4 and S12.x go through the
ingestion, waiver, posture and re-vetting triggers. `GET
/api/v1/snapshots/{id}/auto-approval` is asserted in S12.2 to report the same
evaluation the attempt used.

## Must NOT

- M1: Change any existing test's assertions, or weaken or delete an SVC test.
  The existing approval, four-eyes, re-vetting, revocation and estate suites
  pass unchanged.
- M2: Change `ApprovalService`'s gates or their order (design D8).
- M3: Add `config-reconciler` or `auto-approver` to
  `FourEyesGate.NON_HUMAN_ACTORS` (design D4).
- M4: Change the human approval's ledger entries or webhook (S10.1, pinned
  before the move).
- M5: Change behaviour with the defaults: posture `manual`, mode `off`,
  `required-vetters` unset (F22).
- M6: Make a breaking API change. Every new field and endpoint is additive;
  the committed `openapi.json` is regenerated and the breaking-change gate
  passes.
- M7: Add a backend package, a grantable role or a scheduled sweep. Raise
  `ConfigSurfaceBudgetTests` by exactly three (R4); `ContextBudgetTests` by
  exactly one, for `AutoApprovalTests` (R5).
- M8: Run subprocess git in production code.
- M9: Put a rule in the portal that the server does not enforce, or change a
  portal page other than the marketplace Settings page. Existing stories and
  e2e tests pass unchanged.
- M10: Make the posture and override share one lifetime constant, or tie
  either to `WaiverService.MAX_LIFETIME`.

## Gauntlet

| Layer | How |
|---|---|
| Full test suite | `./mvnw clean verify`, zero new failures |
| Static types and lint | compilation, Spotless, Checkstyle inside `verify` |
| Coverage on changed lines | **substituted**: no coverage tool is in the build, and adding JaCoCo is out of scope. Every new class and branch is named by a scenario above; mutation below checks they are asserted |
| Mutation | `mutants.sh`, manual, at least one mutant per condition of the eligibility predicate (D5–D7), the URL-prefix matcher, the `required-vetters` check and its startup refusals, the posture and override expiry checks, the mode switch, the re-vetting enforcement and the withdrawal filter. Every mutant must be killed by the test it names; a survivor is a missing test |
| Property-based | **substituted**: no property-testing library in the build. The URL-prefix matcher gets a table-driven test over a generated set of hostile variants instead |
| Adversarial pass | one explicit step attacking the finished predicate: a test per bypass idea tried, kept as regression tests, listed in `evidence.md` |
| Real execution | the packaged jar with `mode: shadow` and then `mode: on`, an internal hosted marketplace, one Markdown-only publish: observe `snapshot-auto-approval-would-approve`, then the served tip moving, with the ledger excerpts in `evidence.md` |
| Portal | the S15 stories under `pnpm test:stories`, S15.5 under `pnpm e2e`, `/impeccable audit` and `/impeccable harden` on the Settings page, and before/after screenshots in the PR body |
| Supply chain and secrets | no dependency change; the diff is scanned for secrets |
| Suite health | the new test classes run twice in different orders |
| Other gates | `pnpm test:stories`, `pnpm e2e`, `reqstool status local -p docs/reqstool`, `openspec validate --all --strict`, `mkdocs build --strict` |

## Open questions (carried from the design, not decided here)

- The inert extension list stays as designed. Shadow mode on the adopter's
  marketplaces answers whether it is complete before `on`.
- No early warning before a posture expires; the posture endpoint shows the
  date.

## Revisions

Made before approval, at the owner's request:

- R1: the `auto` posture's expiry limit is 90 days, not 365, as its own
  constant (S1.2, M10).
- R2: the override is set through the portal as well as the API; the portal
  card is `GW_APPROVAL_0044.15 — An administrator can view and set a
  marketplace's approval posture and override in the portal` (S15, F25, F26).
  The former M9, "no portal changes", is replaced.
- R3: which vetters must have run is configurable gateway-wide with
  `skills-gateway.approval.auto.required-vetters`, unset meaning every vetter
  (S7.7–S7.10, F23, F24). The configuration budget rises by six, not five
  (M7). The estate scenarios are renamed from S15 to ES.

Made during implementation, a correction of fact:

- R4: `ConfigSurfaceBudgetTests` rises by three, to 112, not six. Its walk does
  not descend into a list's element, and the three estate keys sit inside the
  estate marketplace list's element, as the `upstream-github-app` change's
  block does. The proposal still argues all six settable names. M7 now reads
  "by exactly three".
- R5, approved by the owner on 2026-11-15: `ContextBudgetTests` rises by one,
  to 28, for `AutoApprovalTests`. E1, the triggers and the mode scenarios need
  a gateway with `approval.auto.mode=on` and `vetting.revet.enabled=true`, and
  design D12 makes the attempt refuse to act while re-vetting is off. The
  shared context turns re-vetting off so no sweep reaches other suites'
  fixtures, and no existing context has it on. The new context sets a long
  sweep interval, so no background tick runs during the suite. M7 now reads
  "by exactly one".
- R6, a contradiction resolved fail-closed, for the owner to confirm: design D7
  made a manifest JSON file never inert, but every marketplace has
  `.claude-plugin/marketplace.json`, so S8.2's inert first snapshot could never
  be approved. The manifest and `plugin.json` now count as inert only when all
  their keys are on a fixed descriptive list; any other key holds the snapshot.
  A side effect: a manifest edit that changes only descriptive fields, such as
  a version bump, no longer holds an update.
- R7, a gap resolved fail-closed, for the owner to confirm: S12.2 says that
  under `shadow` "every check runs". `ApprovalService`'s gates (closure,
  revocation, policy, name collision, release age, four-eyes) run only inside
  `approve`, which publishes, so shadow cannot run them without publishing.
  Shadow runs D8's six checks, and a `snapshot-auto-approval-would-approve`
  entry means "eligible, subject to the approval gates". D8 now says so.
- R8, a correction of design D10: the triggers are synchronous Spring
  application events, `HeldSnapshotChanged` and `RevetTick`, not direct calls.
  `AutoApprovalService` is constructed with `WaiverService` and
  `VettingService`, so a direct call back from `WaiverService` would be a
  constructor-injection cycle; one event serves every publisher, and the
  publishers need no reference to `approval`. The listener is a
  `@TransactionalEventListener` with `fallbackExecution = true`: it runs after
  the commit when there is a transaction, and at once when there is none, so
  D10's objection that an after-commit listener would never fire does not
  apply. The ingestion event is still published after `ingest` returns,
  outside the ingestion lock.
- R9, a refinement of design D13, for the owner to confirm: with
  `serveAfter: previous_approved`, a marketplace whose served tip is withdrawn
  returns to its newest approved snapshot that is not also being withdrawn.
  Revoking newest first one at a time would otherwise republish each
  intermediate automatic approval for a moment before withdrawing it. A
  withdrawn snapshot that is not the served tip leaves the served reference
  alone. When a marketplace has no snapshot to return to, the whole request is
  refused before anything is withdrawn, as a single withdrawal is today
  (GW_APPROVAL_0016 — What a marketplace serves after a revocation is a stated
  choice). A snapshot that someone else withdraws during the request is
  skipped rather than failing it.
- R10, a correction of this change's own group 4 implementation to the approved
  design: `GET /api/v1/marketplaces/{name}/approval-posture` had been made
  administrator-only, but D14 restricts only `PUT`, and D15 and S15.3 have a
  non-administrator see the card. The read is now open to any session, as
  `GET /api/v1/snapshots/{id}/auto-approval` already was, and setting a posture
  stays administrator-only. The response gains `mode`, the deployment's mode,
  which D15 needs on the card. Neither the route nor its tests exist on `main`.
- R11, a correction of design D15, for the owner to confirm: D15 says the
  form does not pre-empt the server's limits beyond the date picker's
  maximum. The repository's forms rule (`.claude/skills/design-conventions`,
  "Disabled until valid" and "The client mirrors the server, exactly")
  outranks that, so **Save posture** stays disabled until each required
  reason is not blank and each date runs from tomorrow to 90 days out. Those
  are the server's own rules, copied exactly. The one difference is that a
  date cannot say "later today", so tomorrow is the earliest choice. Every
  other refusal (no internal basis, four-eyes, an override on an internal
  marketplace) stays the server's, and is shown as returned with its next
  step.
- R12, a gap in the design, for the owner to confirm: the design does not
  classify the five new routes for machine credentials, and
  `MachineApiRegistryTests` requires every `/api/**` route to be classified.
  Setting a posture, revoking an override, and the recall
  (`POST /api/v1/approval/auto/withdraw`) are added to the unreachable
  table: a grant that lets content publish with no person, and two
  retractions, belong with approve, revoke and waivers. The two reads join
  existing read scopes rather than adding a scope: the posture joins
  `marketplaces:read`, as marketplace metadata every session already reads,
  and the evaluation joins `snapshots:read` beside `four-eyes` and
  `name-collisions`, the same kind of "what an approval would meet" read.
  `MachineApiScopeTests` names the three unreachable routes among the
  load-bearing exclusions (GW_APPROVAL_0044 — An administrator can opt an
  internal marketplace into automatic approval of snapshots that need no
  human judgement).

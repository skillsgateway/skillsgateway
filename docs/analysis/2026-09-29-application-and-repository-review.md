# Application and repository review — 2026-09-29

Reviewed at `63e8a0c7` (`main`, after #533). The third review in this
directory, nine days after the 1.0.0 readiness review and three weeks after the
architecture assessment. Both of those were worked to completion, so this one
does not repeat them: it asks what is left once their findings shipped, and it
widens the lens to the **GitHub repository itself** — rulesets, workflows,
alerts, release path — which neither predecessor covered.

Read-only against the code; the repository settings were read live through the
GitHub API rather than from `safe-settings/`. Nothing was built in this session;
the build evidence below comes from CI and from a local run earlier today.
Every claim carries the file and line, or the API object, that proves it.

**The short version.** The trust boundaries hold. The facade, the publication
endpoint, the machine API, the approval gates, the SSRF layer for
manifest-driven fetches and the schema's invariants are in the state the
documentation claims, and the route table is derived rather than hand-listed on
both the mutation and the read side — the gap the 2026-09-08 assessment's F5
named is closed (`RoleEnforcementTests.java:579-588`). What remains is
governance and posture: a default that is open where the rest of the product
refuses, a repository rule that is decorative for a solo maintainer, a primary
control that exists only as prose, and a test suite whose flakes are known only
outside the repository.

**Nothing here is a disclosure.** Every weakness below is already stated in the
public documentation or visible in the public repository settings; none is an
exploitable defect in the code. That is why this is published on the day it was
written rather than held like the previous two.

---

## Should-fix

Ordered by what it would cost to leave alone, not by severity.

### 1. The identity token's issuer is unchecked by default (AUTH)

`SecurityConfig.java:188-199` builds the ID-token decoder and, when
`skills-gateway.oidc.issuer` is unset, logs a warning and validates no issuer.
`helm/skills-gateway/values.yaml` ships `oidc.issuer: ""` with the comment
"Unset means the issuer is not checked at all — on a multi-tenant endpoint that
is the tenant boundary left open", and `trust-boundaries.md` §6 says the same.
The provider endpoints are configured explicitly rather than discovered, so
Spring never learns the issuer on its own.

The consequence is exactly what the comment says: against an identity provider
whose authorization endpoint serves many tenants with one key set, a token
issued to any tenant for this client id verifies. The audience check is not the
boundary there; the issuer is.

**Why this is out of character.** Every other posture decision in this codebase
refuses rather than defaults. `DevInsecureAuthGuard.java:57-65` refuses to start
when the escape hatch meets a configured provider; `RoleBootstrapGuard`
refuses a gateway with no administrator; `TokenService.validateMachineTtl`
refuses a missing expiry "never defaulted". The issuer is the one credential
boundary that warns instead.

**Fix.** Refuse to start when a real provider is configured and the issuer is
not. The signal already exists — `DevInsecureAuthGuard.identityProviderSignals`
(`:76-91`) is precisely "is a provider configured" — and `oidc.issuer` already
exists as a leaf, so this adds no configuration surface and removes a default.
It is a breaking change for a deployment that never set the issuer, which
pre-1.0 is free; the chart's `oidc.issuer` becomes `required`. If a deployment
genuinely needs an unchecked issuer, that is the case to make explicit, not the
default to keep.

### 2. The ruleset is bypassable by design, and it is bypassed on every PR (REPO)

The live ruleset `protect-main` (id 20992979) carries thirteen required checks,
one approving review, thread resolution, no force-push and no deletion — and a
single bypass actor: `OrganizationAdmin`, mode `always`. There is no tag ruleset
(`rulesets?targets=tag` → 0), and `strict_required_status_checks_policy` is
`false`.

Three consequences:

- **The review rule is decorative.** GitHub does not let an author approve
  their own pull request, and the repository has two collaborators,
  one admin and one with write access. Every owner PR therefore merges through
  the bypass — which bypasses *all* rules for that merge, the thirteen checks
  included. The rule that reads "one review and green checks" enforces, for the
  person who merges most PRs, nothing.
- **`always` permits a direct push.** Bypass mode `always` lets the actor push
  to `main` without a pull request at all. A compromised admin token lands code
  on `main` with no check having run. Mode `pull_request` would keep "only via a
  PR" enforceable even for the bypass actor.
- **Release tags are unprotected.** `release.yml` states "the git tag is the
  only version" and re-checks that the tag is absent before creating it — but
  nothing prevents an existing tag from being deleted and recreated at another
  commit by either collaborator. A tag ruleset (`refs/tags/*`: no deletion, no
  update) is one object.

**Fix, in the `skillsgateway/.github` `safe-settings/` source.** Drop
`required_approving_review_count` to 0 — honest for a repository whose author
cannot review — and remove the `always` bypass, or narrow it to `pull_request`
if an emergency route is wanted. Keep every check required. Add the tag
ruleset. The net effect is *stronger* than today: thirteen checks that cannot be
bypassed, instead of one review that always is. `CONTRIBUTING.md` "Repository
settings and labels" then needs its check list updated — it names seven checks
where the ruleset requires thirteen (it omits the three CodeQL analyses, DCO and
`Apply subsystem labels`).

### 3. The primary SSRF control exists only as prose (DEPLOY)

ADR 0011 §3 (`0011-external-plugin-sources.md:101-110`) and
`trust-boundaries.md:165-175` are unambiguous: the scheme allowlist, the host
allowlist and the private-address refusal are *defence in depth*; "the primary
control is network topology: ingestion egress routed through a proxy or DMZ with
no route to cloud metadata endpoints". `trust-boundaries.md:70-72` narrows the
in-application layer further: "Address-level egress controls apply to external
plugin sources only, not to a marketplace upstream an administrator names" —
and by the same token not to a webhook subscriber URL
(`WebhookService.java:299`, scheme only) or the mirror URL
(`MirrorUrlPolicy.java:28-48`, scheme and userinfo only).

The Helm chart ships seven templates and no `NetworkPolicy`. The Kubernetes
guide's only egress sentence is that Fargate pods need a NAT path
(`deploying-on-kubernetes.md:368-370`) — the opposite concern. So the control
the ADR calls primary has no artifact and no instruction; an operator who
follows the guide end to end deploys a gateway whose admin-supplied URLs can
reach `169.254.169.254` and every RFC 1918 address the cluster routes.

**Fix, smallest first.** A section in the Kubernetes guide with a concrete
egress `NetworkPolicy`: allow DNS, PostgreSQL, the object store, and the
forges the estate names; deny link-local and RFC 1918 with `ipBlock.except`.
That is documentation and adds nothing to the capability map. An opt-in
`networkPolicy.enabled` template in the chart is the next step if the guide
alone proves insufficient; it belongs under the existing "Release and
packaging" row and is a deployment artifact, not a capability.

### 4. The flaky suites are known only outside the repository (TESTS)

Of the last 100 `CI` runs on pull requests, 9 were red; of the last 40 on
`main`, 2. Sampling five of the red PR runs:

| Run | Branch | What failed |
| --- | --- | --- |
| 36560334768 | `docs/risk-findings-not-tiers` (docs-only) | `UpstreamGitHubAppIntegrationTests.each_api_refusal_is_reported_with_its_own_reason` — expected "could not be reached", got the previous case's "did not issue a token" |
| 36112038530 | `fix/sink-channel-delete-guard` | `CatalogTests.constituents_audit_manual_rebuild_and_the_reserved_name` — 500 instead of 200 |
| 36162404198 | `renovate/aws-java-sdk-v2-monorepo` | `CatalogTests.the_catalog_aggregates_exactly_the_served_estate_under_namespaced_paths` |
| 35833507976 | `feat/remove-marketplace` (2026-09-23, before #478 merged that day) | `VettingChainEstateGovernanceTests` — order dependence, since fixed |
| 36566256238 | `renovate/spotless.version` | genuine: three consecutive reds on the same bump |

Three of the five are single-test failures in suites unrelated to the PR, one on
a documentation-only change. The same suites recur across sessions —
`CatalogTests`, `AuditExportTests`, `CsrfEnforcementTests`, the GitHub App
refusal case, and an unreproduced `LOCK_FAILURE` in the object-store
concurrency test — yet the repository has two open issues (#85, #531) and none
of them is this. The knowledge lives in agent session notes.

The build is right not to hide this: `pom.xml` sets no
`rerunFailingTestsCount`, so a flake is a red run, not a green one with a
footnote. But every flake on a Renovate PR blocks auto-merge until a human
re-runs it, which is the merge rhythm's largest single drag.

**Fix.** One issue per suite, each with the failing assertion above, filed by
the owner (this review files nothing). Two have known causes and are test-only
changes needing no OpenSpec change: the GitHub App refusal test's last case
reads the previous case's reason (test isolation), and `CatalogTests` asserts
on the one global catalog repository shared across the run. The others need a
reproduction first.

---

## Could-fix

### 5. The shipped image is never tested on a pull request (CI)

`container-image.yml` triggers on `push: main`, a weekly schedule,
`workflow_dispatch` and `workflow_call` — never `pull_request`. Its smoke test
is the only place the jlink module list, the distroless read-only filesystem and
the four-step product path (register, ingest, approve, clone) are exercised
against the real artifact. A PR that changes `Dockerfile`, the packaging in
`pom.xml` or the chart is green while `main` turns red, which has already
happened once. Add a `pull_request` trigger with a `paths:` filter over those
four files; the publish steps are already gated on `inputs.version`, so
nothing else changes.

Related: with `strict_required_status_checks_policy: false`, a PR green against
an old base can break `main` after a squash. Requiring branches to be up to date
would tax the Renovate flow with constant rebases; a merge queue is the
mechanism built for exactly this combination and Renovate supports it. Worth a
decision, not urgent.

### 6. Pinning is mixed (SUPPLY CHAIN)

Third-party actions are pinned by commit SHA (`oasdiff`, `docker/*`,
`labeler`, the four `skillsgateway/.github` reusable workflows); GitHub-owned
actions are pinned by tag (`actions/checkout@v7.0.1`, `setup-java`, `cache`,
`upload-artifact`, `download-artifact`, `setup-python`, `attest-sbom`,
`deploy-pages`, `upload-pages-artifact`). The repository has
`sha_pinning_required: false` and `allowed_actions: all`.

The two `Dockerfile` bases — `eclipse-temurin:25-jdk` and
`gcr.io/distroless/java-base-debian12:nonroot` — carry no digest, while CI
tests against exactly `25.0.4+7.0.LTS`. The release runtime is whatever the
tag points at on release day, not the JDK the gates ran on.

The org Renovate preset already labels `digest` updates
(`renovate-version-digest`), so pinning by digest is maintained automatically
once done: `helpers:pinGitHubActionDigests` for the actions, `pinDigests: true`
for the Docker manager. One preset line each, then a Renovate PR does the
rest. Separately, the release attests the SBOM
(`actions/attest-sbom`) but not build provenance;
`actions/attest-build-provenance` is one more step in the same job and is what
a consumer's policy engine actually checks.

### 7. No Content-Security-Policy on the web surface (HARDENING)

`SecurityConfig.java` configures no `.headers(...)`, so the portal ships with
Spring Security's defaults and no CSP. The portal renders untrusted
quarantine content — markdown, file contents, vetting messages — but
`markdown-view.tsx:49` deliberately has no HTML pipeline and nothing in
`src/main/frontend/src` touches `innerHTML`, so this is defence in depth
rather than a live hole. It is also cheap here: `index.html` loads nothing
external, fonts are bundled (`@fontsource-variable/geist`), and the API
reference is a webjar (`com.scalar.maven:scalar` serves `scalar.js` from the
classpath), so a `default-src 'self'` policy is within reach. This is a
behaviour change and takes an OpenSpec change and a `GW_AUTH` requirement.

### 8. Drift — small statements that are no longer true (DOCS)

- `V1__init.sql:243` still says "Append-only fetch ledger: no UPDATE/DELETE is
  ever issued against this table". `FetchLogRepository.java:442` issues a
  `DELETE` — the trim behind the export cursor that #464 added, and that
  `snapshots-and-ledger.md:234-252` describes honestly. The schema comment
  is the one copy that was not updated.
- `delegated-administration.md:3` opens with "Out of the box every
  authenticated portal session may do everything." Enforcement has been
  unconditional since GW_AUTH_0025 — Authorization is always enforced — and the
  same page says so at line 82. The sentence describes the removed
  `roles.enabled` era.
- `architecture.md:10` still reads "**Status:** Draft proposal · **Date:**
  2026-08-13". Twenty-one ADRs have since accepted or superseded parts of it and
  every archived change cites its section numbers; it is the canonical
  reference, not a draft.
- `CONTRIBUTING.md`'s required-check list is seven long against a ruleset of
  thirteen (see finding 2).
- The `test` deployment environment exists with no protection rules and no
  workflow references it. Delete it.
- CodeQL alert #13 (open since 2026-09-23, severity high):
  `docs/manual/guides/examples/external-vetter.py:14`,
  `curl\s[^\n|]*\|\s*(ba|z|)sh\b` — `\s` and `[^\n|]*` overlap, so a line of
  `curl` followed by many spaces and no pipe backtracks quadratically. The
  example runs per line over untrusted skill content, so a crafted file slows
  the vetter, which blocks approval fail-closed. Drop the `\s` overlap
  (`\bcurl\b[^|\n]*\|\s*(?:ba|z)?sh\b`) or dismiss the alert with a reason; an
  open high in a public security tab is a bad look either way.

---

## Decisions for the owner

- **Finding 2 is yours alone.** Removing the review requirement is honest;
  removing the `always` bypass takes away your emergency route. The
  recommendation is 0 reviews, no bypass, and a tag ruleset — but it changes
  how you merge.
- **Finding 1 breaks any deployment that never set `oidc.issuer`.** Pre-1.0 that
  is free by your own rule; it still needs a release note.
- **Finding 4 needs issues you have said only you file.** Five of them, or one
  umbrella.
- **Both budgets sit exactly at their ratchet** — 109 configuration leaves
  against `BUDGET = 109` (`ConfigSurfaceBudgetTests.java:71`), 27 contexts
  against `BUDGET = 27` (`ContextBudgetTests.java:77`). That is the mechanism
  working: the next leaf has to argue. Finding 1 removes a default without
  adding a leaf; finding 7 adds none. Nothing here asks you to raise either.
- **Coverage is not measured.** `pom.xml` has no JaCoCo and no PIT; the
  frontend lists `@vitest/coverage-v8` but enforces nothing. The gates are
  specification gates — reqstool, OpenSpec, route-table derivation, budgets —
  and they are stronger than a coverage number. This is recorded so nobody
  later reads "evidence-first" as "coverage-gated". Not a recommendation to
  add one.

---

## What was checked and found sound

Do not re-spend effort here.

- **The five filter chains** (`SecurityConfig.java:64-322`): facade, publish
  and status are PAT-over-Basic, stateless, CSRF-off because no cookie is
  honoured; the hooks chain is anonymous with HMAC one layer down; the machine
  API chain matches `/api/**` *and* a bearer header, is deny-by-default from
  `MachineApiRegistry`, and sits ahead of the web chain so the dev hatch never
  opens it; the web chain is OIDC plus `CsrfConfigurer::spa`, with only the
  three health paths open.
- **The read side of authorization is derived, not hand-listed.**
  `RoleEnforcementTests.java:579-588` derives GET routes from
  `RequestMappingHandlerMapping` and requires every one to be in
  `PRIVILEGED_READS`, `APPROVER_SCOPED_READS`, `OWNER_SCOPED_READS` or
  `OPEN_READS` (`:123-197`). The open reads — content inventory, provenance,
  licenses, vetting results, waiver lists, four-eyes standing — match
  `delegated-administration.md`'s "browsing surface" exactly; file contents and
  the blast-radius report are approver-scoped
  (`SnapshotPreviewController.java:60-132`, `RevetController.java:99`).
- **PATs.** 32 random bytes from `SecureRandom`, `sgw_` prefix, SHA-256 stored,
  looked up by hash (`TokenService.java:266-270, 317-319`); scope lists
  validated at issue time; session-derived credentials cannot hold API scope
  and machine credentials must expire, both as `CHECK`s
  (`V1__init.sql:351-366`), not only in the service.
- **The facade** (`GitFacadeConfiguration.java`): `receive-pack` factory null
  (`:85`); name validated, scope enforced and marketplace liveness checked
  *before* storage is opened (`:93-111`); `RefFilter` allowlists `HEAD`,
  `refs/heads/main` and `refs/snapshots/*` (`:52-60`); every `want` is
  ledgered against the advertised set (`:210-217`). The publish servlet is a
  separate object over a separate directory (`GitPublishConfiguration.java:57-68`)
  with deletes refused twice and atomic receive (`:101-112`).
- **The approval path** (`ApprovalService.java:240-368`): removed-marketplace
  check, closure completeness, standing withdrawal, effective vetting outcome,
  policy gate, name collision, release age, four-eyes — in that order, each
  refusal ledgered before it is raised; the transition re-asks the name
  question under the approvals lock (`:562-571`); publication is one seam
  operation and a failed one un-decides the row (`:410-430`), with
  `PublicationReconciler` behind that. Self-reversal of an administrative
  withdrawal is refused in code (`:508-509`) and by `CHECK`
  (`V1__init.sql:623-624`).
- **The SSRF layer for manifest-driven fetches.** `SourceUrlPolicy` refuses
  ambiguous address literals rather than normalising them; `SourceAddressPolicy`
  checks every resolved address, unwraps IPv4-mapped IPv6, and puts link-local,
  metadata and reserved ranges beyond any configuration;
  `GuardedHttpConnectionFactory` pins every request to the origin, decides a
  redirect before the hop, and bounds received bytes on the stream. The DNS
  rebinding gap is stated at `:41-49` with the reason it is bounded today.
- **Inbound webhook**: `MessageDigest.isEqual` and a bounded body read before
  the HMAC (`InboundWebhookController.java:76-108`). **External vetters**:
  connect and read timeouts, response bounded at `maxResponseBytes`
  (`ExternalVettingConnector.java:72-73, 259-262`). **Status endpoint**:
  `MAX_HOLDINGS = 256` (`HeldContentController.java:57`).
- **The sweeps.** Eight `@Scheduled` methods, one `sweep_leases` row each
  (`SweepLeases.java`), lease equals interval, never released early, non-blocking
  acquire. The 2026-09-08 F2 concern (uncoordinated sweeps) is closed. The
  vetting executor is an unbounded cached pool by design (`VettingService.java:91-99`),
  bounded in practice by the per-vetter timeout (`:402-409`) and the connector's
  read timeout.
- **The ledger's promise is stated honestly.** `snapshots-and-ledger.md:234-276`
  says what append-only does and does not mean, that only the two facade read
  events are ever trimmed and only behind every enabled sink, and that the table
  is not self-proving. The README's "append-only" is consistent with that.
- **The image and the chart.** Distroless `nonroot`, jlink module set proven by
  the smoke test, `MaxRAMPercentage` reasoned (`Dockerfile`); chart refuses
  `replicaCount > 1` on the filesystem backend, uses `Recreate` there, seals
  the root filesystem, drops all capabilities, separates liveness from readiness,
  and has nowhere to type a secret.
- **Workflows.** Top-level `permissions: contents: read` everywhere; jobs
  escalate narrowly. `pull_request_target` appears once (`pr-labels.yml`),
  never checks out the PR head, runs a SHA-pinned labeler. The release is
  dispatch-only, gated on the `stable` environment with a required reviewer,
  verifies the uploaded assets over the unauthenticated path, and attests the
  SBOM to the registry. Pages deploys from the workflow, HTTPS enforced.
- **Security features.** Secret scanning and push protection on; CodeQL default
  setup, extended suite, six languages, weekly; Dependabot alerts on with
  Renovate handling vulnerabilities (`vulnerabilityAlerts` and
  `osvVulnerabilityAlerts` in the org preset); zero open Dependabot alerts; a
  security policy at the organization level (`skillsgateway/.github/SECURITY.md`)
  that names the scope a security product should name.
- **Secrets.** No credential-adjacent log line beyond the issuer warning. The
  `BEGIN RSA PRIVATE KEY` in history belongs to `TestKeys.java` fixtures for the
  GitHub App tests. No AWS, GitHub or Slack token shapes in the tree. Every
  runtime secret in the chart comes from a `Secret`; the compose file's
  placeholders are `change-me` and `test`.
- **Process hygiene.** Three branches, two open issues, one open PR, 118
  archived OpenSpec changes and two parked ones that say why they are parked
  — consistent with the stop rule. `clean verify` on this machine earlier today:
  166 suites, 993 tests, 0 failures, 9 skipped.

## Could not verify

- **The gates were not run in this session.** The evidence is CI at `63e8a0c7`
  (every check green on #533's runs) and a local run on this machine today at
  a commit one ahead of `main`. Storybook and e2e were not run here.
- **Behaviour on a real multi-tenant identity provider** (finding 1) is
  inferred from the code and Spring Security's `OidcIdTokenValidator`, not
  exercised.
- **The `safe-settings/` source in `skillsgateway/.github`** was not read; the
  ruleset was read as GitHub reports it. If the declaration and the live object
  disagree, the live object is what enforces.
- **Organization-level rulesets** need `admin:org` scope, which this session's
  token lacks; the repository-level view may not be the whole picture.
- **Whether the flaky suites in finding 4 are five or six** — the sample was
  five runs; a full pass over the red runs' logs would settle the list.

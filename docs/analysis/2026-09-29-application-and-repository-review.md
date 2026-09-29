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

Two passes. The first (findings 1–8) covered the trust boundaries, the gates,
the workflows and the repository settings. The second (findings 9–17 and "The
portal, walked") read every backend package at function level, the schema
against the queries that hit it, and every portal source file, and then opened
every page of the live instance in a browser. The second pass is where the
defects are; the first is where the posture decisions are.

**The short version.** The trust boundaries hold. The facade, the publication
endpoint, the machine API, the approval gates, the SSRF layer for
manifest-driven fetches and the schema's invariants are in the state the
documentation claims, and the route table is derived rather than hand-listed on
both the mutation and the read side — the gap the 2026-09-08 assessment's F5
named is closed (`RoleEnforcementTests.java:579-588`). What remains is
governance and posture: a default that is open where the rest of the product
refuses, a repository rule that is decorative for a solo maintainer, a primary
control that exists only as prose, and a test suite whose flakes are known only
outside the repository. The code pass adds four things to fix rather than
decide: two paths that cannot work on the object-store backend, a portal that
mistakes the newest page of the ledger for the ledger, three ledger reads with
no index and one table with no delete, and a waiver that may expire in 2099.

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

## Should-fix, from the code pass

Same ordering rule: by what it costs to leave alone.

### 9. The catalog and hosted-marketplace ingestion cannot work on the object-store backend (BACKEND)

`CatalogService.vendor` (`CatalogService.java:245`) and the hosted branch of
`IngestionService.fetchIncoming` (`IngestionService.java:232`) both fetch
through `repository.getDirectory().getAbsolutePath()`. A JGit `DfsRepository`
has no directory — `getDirectory()` returns null — and the codebase knows it:
`GitObjectTransfer.java:19-22` records that publication once used the same
expression, raised `NullPointerException` on the object-store backend, "and
nothing detected it because no test drove approval on that backend";
`RefTransitions.java:83` and `HostedPushHook.java:139` both guard the null.
Two call sites were not converted.

On an object-store deployment the catalog is on by default
(`SkillsGatewayProperties.Catalog`), so every approval and every revocation
runs `rebuildQuietly` (`CatalogService.java:136-142`), which catches the
`RuntimeException`, logs "virtual catalog rebuild failed" and serves no
catalog: `/git/catalog` answers 404 and `GET /api/v1/catalog` says "not
generated yet" over an estate that is serving. A hosted marketplace
(GW_FACADE_0006 — Gateway-hosted marketplace origin) cannot be ingested there
at all; `fetchIncoming` throws before the lineage is read.
`ObjectStoreBackendTests` drives hosted storage at the reference-transition
level and never the ingestion path, and no test names the catalog on that
backend.

**Fix.** Both fetches copy objects between two repositories the gateway
already holds open, which is what `GitObjectTransfer.copy` was written for;
use it in both places, and add the two cases to the object-store backend
tests so the third call site cannot appear. Stated by reading, not by running
a bucket — see "Could not verify".

### 10. The portal reads one page of the ledger and calls it the ledger (PORTAL)

`useAudit` (`queries.ts:711-721`) fetches `GET /api/v1/audit` once, keeps
`entries` and discards `nextBefore`. Three surfaces then treat that page — the
newest 1,000 rows, `audit-export.default-page-size` — as the whole ledger:

- The overview's "Fetch ledger" card (`overview.tsx:87`) prints the page's row
  count as "recorded fetches". On the live instance it reads **76 recorded
  fetches** while the adoption page, which asks the ledger the real question
  (GW_OBSERVABILITY_0001 — Adoption reporting from the fetch ledger), reads
  **0 fetches**: 66 of those 76 rows are `vetting-verdict`,
  `vetting-completed` and `revet-clear`. Past 1,000 rows the number freezes.
- The marketplace Activity section (`marketplace-detail.tsx:63-65`) filters
  that page by marketplace name — so a quiet marketplace reads "Nothing
  recorded against this marketplace yet" once 1,000 newer rows belong to
  other marketplaces — and then `reverse()`s it. The page arrives newest first
  (`FetchLogRepository.java:154`, `ORDER BY id DESC`), so Activity renders
  oldest first; verified live, `marketplace-registered` is its top row,
  against its own docstring and against the Audit page one click away. The
  unit tests cannot catch it: the mock ledger answers empty
  (`msw-handlers.ts:1246`), and `audit.test.tsx:120` still says "the API
  answers in ledger order — oldest first", the contract before GW_AUDIT_0008 —
  The ledger browse read is bounded, typed and stably paged.
- The Audit page paginates client-side over the page and offers nothing that
  passes `nextBefore` back; `useAudit`'s own docstring calls it "what a later
  'load older' control would pass back". The download is the only way to an
  older row.

**Fix.** A "load older" control on the Audit page — `useInfiniteQuery` over
`nextBefore`, the shape the diff and tree reads already use. The overview
card reads a count from the ledger, not from a page
(`FetchLogRepository.approximateDepth` already exists for the gauge), and
calls it "ledger entries", which is what it is. Activity asks the server for
its marketplace's rows — a `marketplace` filter on the existing paged read,
additive within the contract — and drops the `reverse()`. The stale test
comment goes with it.

### 11. Three ledger reads scan the table, and one table never shrinks (SCHEMA)

`fetch_log` carries two indexes (`V1__init.sql:294-300`): the partial adoption
index on `(principal, marketplace, id)` and `(actor_type, id)`. Three queries
use neither:

- `fetchersOf(sha)` (`FetchLogRepository.java:189`) — `WHERE sha = ? AND event
  = 'upload-pack'` — behind every blast-radius report and every re-vetting
  violation.
- The retention candidate query (`SnapshotRepository.java:374-376`) — `NOT
  EXISTS (SELECT 1 FROM fetch_log WHERE sha = s.sha AND marketplace = ? AND ts
  > ?)` — evaluated per candidate row, per marketplace, every hourly pass once
  retention is on.
- `adoptionSince` and `marketplaceAdoptionSince` (`:217, :237`), on `ts`.

Each is a sequential scan of the table the documentation calls "the table that
ends the deployment", and the retention one is a scan per snapshot. One index
on `(sha, marketplace, ts)` restricted to `event = 'upload-pack'` covers the
first two and `(ts)` under the same restriction covers the third; `V1` is
rewritten, not migrated, so this costs nothing pre-1.0.

`webhook_deliveries` has one index (`:406`, the dispatcher's) and **no delete
path anywhere under `src/main`**. Every delivery is kept forever with its
payload (`payload TEXT NOT NULL`) — and for an audit sink the payload is the
exported batch, so a sink with the default 500-entry batch writes a second
copy of the ledger into this table beside the one it exports (GW_RETENTION_0009
— Audit ledger read entries are trimmed only behind every sink's export
position — trims `fetch_log` and nothing else). `listBySubscriber`
(`WebhookDeliveryRepository.java:106`) has no index on `subscriber_id`. A
`delivered`/`failed` row's payload is dead weight once the receiver has it: an
age-bounded sweep under the retention pass that already exists adds no
scheduled sweep and at most one leaf.

### 12. A waiver may expire in 2099 (POLICY)

`WaiverService.create` (`WaiverService.java:117-121`) requires an expiry and
requires it to lie in the future; nothing bounds how far. The portal tells the
reviewer "there are no unlimited waivers" (`vetting-report.tsx:155`, the
surface GW_VETTING_0010 — Portal waiver management surface — describes), which
is true in the letter. A waiver is the one act that turns a blocked verdict
into an approval; the ledger entry it writes carries the date, so an auditor
can see a fifty-year waiver, but nothing refuses one.

`Tokens.maxTtl` is the shape: a lifetime past the cap is refused, "never
silently clamped". One comparison here. Whether the cap is a configuration
leaf or a constant is the owner's call — both budgets are at their ratchet
(see Decisions); `HeldContentController.MAX_HOLDINGS` (`:57`) is the precedent
for a bound kept as a constant because no deployment has ever tuned one.

---

## Could-fix, from the code pass

### 13. Two HTTP responses are never closed (BACKEND)

`ExternalVettingConnector.java:123` and `WebhookDispatcher.java:132` call
`RestClient.exchange(fn, false)`; `false` means the caller closes the
`ClientHttpResponse`, and neither does. Both build on
`JdkClientHttpRequestFactory` explicitly, which tolerates it: the vetter reads
its body to the bound, so its connection is normally reusable; the dispatcher
never reads the body at all, so no connection to a subscriber is ever reused
and each one is held until the garbage collector reaches it. A `try` with the
response in both places, or `exchange(fn)` with its default of closing.

### 14. The per-vetter ledger rows are most of the ledger (LEDGER, a decision)

On the live instance 50 of 76 rows are `vetting-verdict` — one per vetter per
run — beside a `vetting-completed` row per run and a `revet-clear` row per
re-vet. The scheduled re-vet (`revet.cadence`, 24 h) therefore writes eight
rows per approved snapshot per day into the compliance ledger to record that
nothing changed, and the per-vetter rows duplicate `vetting_verdicts`, which
is where the portal reads them from. A 200-snapshot estate writes 1,600 rows a
day before a single fetch — the growth the export-cursor trim exists to
absorb, and the noise every Activity view scrolls through. What
GW_VETTING_0012 — Continuous re-vetting of approved snapshots — requires is an
attributable run, which the `vetting_runs` row already is. Whether an
unchanged re-vet needs eight ledger rows, or one, is the owner's decision.

### 15. The diff endpoint re-diffs the whole delta on every page (PERFORMANCE)

`SnapshotPreviewService.diff` (`SnapshotPreviewService.java:501-516`)
computes `toFileHeader(change).toEditList()` and `isBinary` for every changed
path so that `summary` carries whole-diff line counts, then returns one page of
500. A snapshot with 5,000 changed files diffs 5,000 files per page request,
ten pages deep. The paths listing and the tree read page without this. The
pair `(sha, baselineSha)` is immutable, so the summary can be computed once
and cached, or computed only for the first page.

### 16. Smaller backend items

- `IngestionService.ingestLocked` writes `refs/snapshots/<sha>` (`:168`)
  before `snapshotRepository.create` (`:180`); a failed insert leaves a pin
  no row names, and the staging sweep prunes `refs/staging/` only. Insert
  first, or delete the ref when the insert fails.
- `RevetService.revetMarketplace` (`:197`) walks the approved snapshots with
  no per-snapshot `catch`, unlike `sweep` (`:186`): one storage error ends the
  marketplace's re-vet mid-list.
- `VettingService.run` wraps the whole chain loop in `catch (Exception)`
  (`:329, :414`) and records a `snapshot-access` error verdict for anything,
  a database failure mid-loop included, so a blocked snapshot can say "could
  not read the snapshot" about a snapshot that was fine.
- `AdminController.listMarketplaces` (`:450`) runs one snapshot query per
  marketplace, and its response — every snapshot of every marketplace — is
  what every portal page loads first. Fine at ten marketplaces; the first
  thing to page at a hundred.
- `ForgeMetadataService` (`:149`) reads an unknown forge's API answer with
  `body(String.class)` and no size bound on a 3-second timeout; the SSRF layer
  bounds bytes on manifest fetches, not here.
- Six configuration leaves accept zero where the rest of
  `SkillsGatewayProperties` guards `<= 0`: `audit-export.max-page-size`,
  `default-page-size` and `batch-size`, `retention.batch-size`,
  `webhooks.max-attempts` and `batch-size` (`:1298-1355`). `max-page-size: 0`
  makes `Math.clamp(x, 1, 0)` throw on every audit read.
- Three records carry a secret and keep the generated `toString`:
  `ObjectStore.Credentials.secretAccessKey` (`:578`), `DeclaredWebhook.secret`
  (`:699`), `DeclaredAuditSink.secret` (`:708`). `Mirror`,
  `UpstreamCredential` and `GitHubApp` override theirs for this reason
  (`:164, :212, :231`). Nothing logs the properties tree today; the next debug
  line that does prints them.
- `InboundWebhookController` answers 404 for a marketplace that is unknown
  or not in webhook mode and 403 for a bad signature (`:76, :79, :85`),
  unauthenticated. The facade makes absence and refusal indistinguishable on
  purpose (GW_AUTH_0006 — Marketplace-scoped access tokens); this endpoint
  enumerates names. One status for both.

### 17. Portal: session expiry, the clone snippet, one raw timestamp

- The web chain answers an unauthenticated `/api/**` call with a bare 401
  (`SecurityConfig.java:243-244, 318-319`) and the client turns it into an
  `ApiError` whose message is "401 Unauthorized" (`client.ts:32-43`). After
  the session cookie expires every page shows that string in its alert and
  every button toasts it; nothing sends the reader back through login, and
  `useMe` is cached forever (`queries.ts:56`), so the shell keeps saying
  "Signed in as". One check in `api()` — a 401 on a same-origin call means
  reload — is the whole fix.
- The setup wizard's "clone directly" snippet (`setup-wizard.tsx:262`) embeds
  the token in the URL: `git clone https://token:<PAT>@host/git/m`. git writes
  that URL into the clone's `.git/config` in cleartext and the shell into its
  history. The first snippet already stores the credential in the helper; the
  clone line should rely on it and carry no userinfo. The "CI and other
  clients" wording invites exactly the copy that persists it.
- `RevetPanel` prints `fetcher.lastFetch` raw (`snapshot-card.tsx:177`), the
  one instant in the portal not rendered through `Timestamp`.

---

## The portal, walked

Every page was opened on the live instance (`0.4.0-20-SNAPSHOT`,
`dev-insecure-auth`, an admin session): overview, review queue, marketplaces,
one marketplace's review with all five evidence tabs, its snapshots, activity
and settings with the chain controls, the estate-wide vetting page, both
integration pages, tokens, adoption, a marketplace that does not exist, the
user menu and the waiver form. No console errors on any of them.

What it looks like: one design language throughout. The verdict vocabulary —
pass, warn, fail, blocked, clear with waivers — is the same word in the same
colour on the review queue, the marketplace row, the snapshot card, the flow
drawing and the ledger; every state carries a word beside its colour; every
disabled control says why beside itself; every mutation reports its refusal in
the server's own words. The flow drawing — "Blocked at step 1 · secret-scan
found 1 critical finding" over the chain — is the best screen in the product:
the reviewer reads the answer before the evidence and opens the node for the
why. The waiver form defaults to the narrowest scope on offer and refuses a
blank justification. A cold load of an address the router does not know
answers `application/problem+json` rather than a page, which is right for an
admin tool and worth knowing.

Findings 10 and 17 are what the walk turned up; nothing else looked wrong.
Not walked: a narrow viewport (the browser refused the resize) and a session
without the admin role.

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
- **Finding 12 needs a number.** A maximum waiver lifetime as a constant keeps
  the configuration budget where it is; as a leaf it is the first argument the
  ratchet has had to hear. Ninety days is what `Tokens.DEFAULT_MACHINE_MAX_TTL`
  already reasons for.
- **Finding 14 is a ledger-shape decision.** Eight rows per unchanged re-vet is
  what the requirement's literal reading produces; one row per run is what its
  purpose needs. Changing it changes what a SIEM has been ingesting.
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
- **The approval lock and the storage seam, function by function.**
  `NameCollisionGate` takes `pg_advisory_xact_lock` and re-asks the question
  inside the transaction; `ManifestStore.transact` is a bounded
  compare-and-swap with a write-ahead entry and a torn-read retry;
  `RefTransitions` refuses a move it did not make; `PublicationReconciler`
  repairs a half-publication at startup. `SqlArrays.literal` escapes; every
  enum is native; `snapshots` has `UNIQUE (marketplace_id, sha)` and
  `access_tokens.token_hash` is unique; every foreign key's `ON DELETE` matches
  the owner's lifetime (cascade under a snapshot, restrict from a sink to its
  channel).
- **The credential paths, function by function.** `MachineApiRegistry` is an
  allowlist whose omissions fail the build; `MachineApiAuthenticationFilter`
  holds no session, refuses a request carrying both a bearer and a cookie, and
  answers every refusal alike; `IdpBearerConfiguration` refuses to start
  without a pinned issuer, checks audience by containment and refuses a token
  with none; `GitHubAppTokens` validates repository names by pattern before
  they reach a URL; `SkillFrontmatter` parses YAML through `SafeConstructor`
  with alias, depth and code-point limits; `CelPolicy` bounds comprehensions.
- **The read surfaces are bounded.** Every preview read is paged with the total
  stated beside the page; blobs are cut at 128 KiB with the cut stated; the
  status endpoint refuses more than 256 pairs; the inbound webhook bounds the
  body before the HMAC; the external vetter bounds the response.
- **The portal's data layer.** One client, CSRF header on every call, RFC 7807
  `detail` surfaced verbatim; the API types are generated and the contract test
  refuses drift; every mutation invalidates what it changes and toasts what it
  cannot; Markdown renders with no HTML pipeline; the Storybook gate runs axe
  as an error; ARIA names on every control that needs one (88 `aria-label`s,
  42 live regions across the source).
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
- **Finding 9 is by reading.** No bucket was run in this session. The three
  pieces of in-repository evidence — `GitObjectTransfer`'s own account of the
  same defect, and the two null guards — are what the claim rests on; a test
  that approves into the catalog on `ObjectStoreTestSupport` would settle it
  in one run.
- **The portal below 1,000 pixels wide** was not seen; the browser declined the
  resize. The shell's sidebar is a fixed 15 rem with no collapse in the source,
  so a phone-width review is at least worth one look.
- **A non-admin session** was not walked; the live instance runs the
  development escape hatch, which is admin by construction.

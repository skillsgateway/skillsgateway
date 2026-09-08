# ADR 0017 — Virtual catalogs stay derived views: they may subtract, never substitute

*Proposed, 2026-09-08. Decomposes
[#11](https://github.com/skillsgateway/skillsgateway/issues/11); constrained by
[ADR 0008](0008-serving-surface-stays-the-embedded-facade.md), which made the
embedded facade the canonical serving surface.*

## Context

Issue #11 is three lines long and each bullet is a feature:

- federate multiple upstream marketplaces into namespaced catalogs;
- let team/client entitlements decide which approved plugins are visible;
- approve a snapshot but serve a **filtered** manifest, on the content
  inventory `GW_INGEST_0008 — Snapshot content inventory` already exposes;
- publish approved snapshots on more than one ref.

The first of those shipped: `GW_FACADE_0003 — Global virtual catalog`,
`GW_FACADE_0004 — Catalog freshness on publication changes` and
`GW_FACADE_0005 — Catalog provenance, audit, and the reserved name` synthesize one
repository at `/git/catalog` that vendors every marketplace's currently served
snapshot under a subdirectory and merges their manifests into one. The rest is
open, and the standing assessment on the issue (2026-08-18) ordered it
"per-plugin limiting, then multi-ref, then team entitlements" and marked team
entitlements blocked on `#66`.

Two things have changed since that assessment, and both bear on the order.

**`#66` closed.** Roles now derive from an identity provider's own group or
application-role claims by configured mapping (`GW_AUTH_0015 — Identity-provider
claim to role mapping`). The stated blocker is gone — but it
was the wrong blocker. Group-to-**role** mapping gives the gateway a way to read
a group claim; it does not give it a team, and, more importantly, it does not
give the **facade** anything at all. Role enforcement lives at the REST API. The
facade's authorization is token scopes, and that is a different thing than it
looks (below).

**Reading the shipped catalog closely turned up two defects that any filtering
feature would inherit and amplify.** They are what this ADR is mostly about,
because they change what should be built first.

A third fact is worth stating before the defects, because two of #11's bullets
assume otherwise: **the content inventory is not stored.**
`SnapshotContentService` derives it live on every request, by opening the
**quarantine** repository at the snapshot's commit, reading
`.claude-plugin/marketplace.json`, and walking for `…/skills/*/SKILL.md`. There
is no inventory table; `V1__init.sql` has none. What *is* persisted per plugin —
`snapshot_closure_members` with `graft_path` and `tree_sha` — exists only for
*external* sources of a composite snapshot, never for local plugins and never
for skills.

### Defect 1 — the catalog's namespace mapping is not injective

`CatalogService.mergePlugin` derives a catalog plugin name by string
concatenation:

```java
String prefixed = marketplace + "-" + plugin.path("name").asText("unnamed");
String owner = pluginOwners.putIfAbsent(prefixed, marketplace);
if (owner != null) {
    log.warn("catalog plugin name collision: … keeping the first", …);
    return;
}
```

Constituents are iterated in ascending marketplace-name order, so the rule is
*first in marketplace-name order wins*. Marketplace names match
`^[a-z0-9][a-z0-9_-]*$`, which contains the separator, so `marketplace + "-" +
plugin` is a many-to-one map: marketplace `a` with plugin `b-c` and marketplace
`a-b` with plugin `c` both produce `a-b-c`.

The guide already records this as a known limit — "keeps the first plugin in
marketplace-name order and logs the other" — and frames it as an availability
nuisance. It is not. The loser is not merely absent: the winner is **published
under the name the loser's consumers install**. A developer running
`claude plugin install a-b-c`, expecting marketplace `a-b`'s plugin `c`, is
served marketplace `a`'s plugin `b-c` instead, from a different publisher, with
no in-band signal that anything happened. That is content substitution across a
publisher boundary, reachable by anyone who can get a marketplace registered
with a name that sorts earlier — and registration is the trust boundary the
product names.

The substitution is also **retroactive**: nothing about the incumbent changes.
Registering an unrelated marketplace re-decides an existing install name at the
next approval-triggered rebuild.

What exists to notice it: one `log.warn`. There is no ledger event, no metric,
no field on `GET /api/catalog`, no portal surface. `CatalogInfo.constituents`
is per-marketplace, so the losing marketplace still appears as a constituent
with its subtree vendored — only its manifest entry is gone.

### Defect 2 — a catalog revision on the ledger may no longer resolve

Fetches of `/git/catalog` land on the ledger like any marketplace, carrying the
catalog commit SHA. The composition of that commit is recorded **in the commit
message** and read back by `CatalogService.served()`, which parses the commit
that `refs/heads/main` points to *now*.

Every rebuild force-updates that ref to a new **parentless** commit. Parentless
is deliberate and correct — it is what makes a retracted constituent
unreachable the moment the next revision lands. The cost is that the previous
composition becomes unreachable at the same instant, and eventually
unreadable. So the ledger holds a SHA whose contents the gateway can no longer
describe.

Today that is a small gap: the constituents of an old global catalog can be
approximated from the approval history. Under filtering it stops being
approximable, because the composition then depends on exclusions and
entitlements that are mutable state with their own history. "What exactly did we
serve this team on the 3rd" is precisely the question a filtered catalog exists
to make someone ask, and it is the question that would not answer.

### What the facade does and does not decide today

This is the fact that reorders the issue's last bullet.

A personal access token may carry `scopes`, a list of marketplace names, and
`GitFacadeConfiguration` enforces them at repository resolution — an
out-of-scope fetch answers 404, indistinguishably from a marketplace that does
not exist (`GW_AUTH_0006 — Marketplace-scoped access tokens`). That looks like
per-identity authorization. It is not:

- a token **without** scopes reaches every marketplace, which is what every
  pre-scoping token meant; and
- tokens are owner-scoped — every principal creates their own, and chooses its
  scopes.

So scopes are a *self-imposed* blast-radius limit, not an entitlement. Any
principal who can authenticate to the facade at all can reach every published
marketplace, by minting an unscoped token. The facade authenticates; it does not
authorize per identity. `RoleService` is not consulted on this path at all, by
design — the roles (`admin`, `approver`, `auditor`, a PostgreSQL
`role_grant_role` enum) govern the REST API, and they are administrative roles,
not consumption entitlements. A team is not a fourth role, and putting it in
`role_grants` would conflate the authority to *administer* a marketplace with
the entitlement to *consume* from it.

"Team X may see plugin Y" is therefore not a filter over an existing
authorization model. It is the gateway's **first per-identity deny on the
serving surface**, and building it wrongly — as a token scope, or as a catalog
that merely omits things — produces a control that is bypassed by fetching
`/git/{marketplace}` directly.

## The question this ADR answers

Not "how do we build per-team catalogs". It is: **what is a catalog allowed to
be, such that a filtered or entitled one does not quietly weaken the approval
gate, the ledger, or ADR 0008** — and, given that answer, which of #11's four
bullets should be built, in what order, and which should not exist here at all.

## Options considered

### For the shape of a filtered catalog

**A. A catalog is a synthesized repository, served by the ordinary facade, whose
content is strictly derived from what is already published.** What the shipped
global catalog is. A filtered or per-team catalog is the same object with a
smaller constituent set. *Chosen.*

**B. Filter the marketplace's own published repository per audience.** Serve
`/git/acme` differently depending on who asks. Rejected: it makes the served
bytes at a stable URL depend on the caller, so a snapshot SHA no longer
identifies content, `refs/snapshots/<sha>` stops being meaningful, and the
ledger's `sha` column stops pinning what was delivered. It also puts the
approval record and the served artifact permanently out of correspondence, which
is the thing this ADR exists to prevent.

**C. A manifest-resolution API — the client asks the gateway which plugins it
may see and gets JSON.** Rejected: it is a second serving surface with its own
authentication and its own audit, which is exactly what ADR 0008 declined to
accept from a forge. It would also not work: clients speak git to a marketplace
URL, so the JSON would have to be advisory, and an advisory filter is not one.

**D. Mutate the approved snapshot on approval — approve with exclusions
applied.** Rejected outright: the served bytes would then differ from the
snapshot that was vetted and whose SHA the ledger and every attestation record.
This is the option that makes "the thing served is no longer the thing approved"
literally true, and it is the reason the invariant below is stated as a
prohibition rather than a preference.

### For collision semantics

**A'. Keep "first in marketplace-name order wins".** The status quo. Rejected:
order-dependent, retroactively re-decidable by an unrelated registration, and
substitutes one publisher's content under another's name.

**B'. Pick a separator that cannot collide.** There is none available: both `-`
and `_` are legal in a marketplace name, and the plugin half of the name is
upstream-controlled, so no ASCII separator is reserved on both sides. A
length-prefixed or escaped encoding would be injective and would produce install
names no human would type. Rejected on those grounds, and because it renames
every plugin in every estate that has already installed one.

**C'. Refuse the colliding registration.** Refuse to register `a-b` while `a`
exists. Clean, and it puts the control at the trust boundary — but `acme` and
`acme-tools` are ordinary names, so it refuses estates that have done nothing
wrong, and it cannot help an estate that already contains both.

**D'. Publish neither colliding entry, loudly.** On collision, both entries are
omitted from the merged manifest, a ledger event names both sources, the
collision appears on `GET /api/catalog`, and a metric counts it. *Chosen.* It
converts a silent substitution into a loud absence, which is the fail-closed
direction: an absent plugin is a support ticket, a substituted one is an
incident. Combined with a **warning** (never a refusal) at registration when a
new name is a prefix of, or prefixed by, an existing one — the only collision
source a registrant controls.

### For entitlement

**A''. Entitlement as a token scope.** Rejected — see above; scopes are
self-chosen, and widening one is a token creation away.

**B''. Entitlement as a per-identity deny evaluated at facade repository
resolution**, beside the existing scope check, over state the principal cannot
grant themselves. *Chosen in principle, deferred in practice* — it is the
correct place and it is a trust-boundary change that has to be built
deliberately, not as a side effect of a catalog feature.

**C''. Entitlement as visibility only — a per-team catalog that omits things,
with `/git/{marketplace}` still open to every authenticated principal.**
Rejected as a *security* answer and accepted as an honest *curation* answer.
This is the distinction the rest of the ADR turns on.

## Decision

### 1. The invariant: a catalog is a view, and a view may subtract but never substitute

Written so later work is checkable against it:

- **What is approved is a snapshot, and `/git/{marketplace}` serves it
  byte-identical.** No catalog feature changes this. Filtering never edits, and
  never causes the gateway to serve, a modified approved snapshot.
- **A catalog is derived content**, assembled only from what published
  repositories are serving at that instant, and it is never an input to
  approval, vetting, retention or revocation.
- **A catalog may omit** a constituent or a plugin. It may never present content
  under a name that, in any other catalog or in any other revision of the same
  catalog, denotes different content. Omission is legitimate; substitution is
  not.
- **Every catalog is served by the ordinary facade** at `/git/{name}`, through
  the same servlet, the same PAT authentication, the same served-ref allowlist
  and the same ledger hooks. This is the test to apply to any proposed catalog
  feature: *if it needs a serving code path the facade does not already have, it
  is a second surface and ADR 0008 says no.*

### 2. Exclusion is curation. Revocation is the security control. The docs say so

A per-plugin exclusion removes a plugin from a synthesized catalog. It does not
remove it from the marketplace's own published repository, which the same
credential can still fetch at `/git/{marketplace}`. Therefore:

**A per-plugin exclusion is not a containment control and must never be
documented, named or surfaced as one.** Where content must stop being reachable,
the existing control is revocation — `GW_VETTING_0013 — Auto-quarantine of a violating
approved snapshot` and `GW_VETTING_0016 — Re-vetting violations are announced with their
affected consumers` — which removes both published references and produces the
blast-radius report.

Given that, exclusion still does the honest job of *not advertising* a plugin —
and it should do it thoroughly rather than half-way. When a plugin is excluded,
the catalog omits **both its manifest entry and its source tree** from the
vendored subtree. A filter that leaves the bytes one `git clone` away invites
being reported as a bypass of a control it never was, and the resulting catalog
is honest about itself.

Two mechanics follow from where the inventory actually lives:

- **Plugin-level exclusion needs no inventory service.** `CatalogService.vendor`
  already parses each constituent's *published* manifest to merge it; excluding
  an entry is a filter in that loop, and pruning its subtree is a
  `TreeFormatter` that omits one path. `SnapshotContentService` reads
  **quarantine**, which the catalog must never consult, so the catalog does its
  own reading — as it does today.
- **Skill-level exclusion is materially harder and is deferred.** The
  `…/skills/*/SKILL.md` discovery rule exists only inside
  `SnapshotContentService`, bound to quarantine, and removing a skill directory
  means rewriting a plugin's tree rather than omitting a path. Either that rule
  becomes repository-agnostic and shared, or the walk is duplicated — a
  decision that should be made when skill-level exclusion is actually built, not
  inherited from a plugin-level change. #11 asks for "per-plugin / per-skill";
  this ADR grants per-plugin and names per-skill as a separate decision.

An exclusion is API-managed runtime state, so it is a **sixth declarative estate
object type** — `skills-gateway.estate.catalog-exclusions`, reconciled beside
`marketplaces`, `grants`, `webhooks`, `audit-sinks` and `policy-rules`, in the
same change that introduces it. The reconciler's additive-never-destructive
contract has a pleasant consequence here that should be stated rather than
discovered: removing an exclusion line does **not** lift the exclusion. For a
deny list that is the safe direction — an exclusion outliving its declaration
fails towards withholding — and lifting one stays a deliberate act.

The exclusion key is `(marketplace, plugin name)`, not a snapshot id: an
exclusion is a standing statement about a plugin, and re-ingestion, re-vetting
and retention must not silently lift it. An exclusion naming a plugin that no
longer appears in the marketplace's served manifest is **retained and
reported**, never pruned — a plugin that comes back must come back excluded.

### 3. Collision semantics become specified behaviour, fail-closed and audible

`<marketplace>-<plugin>` stays the naming scheme — it is what shipped, consumers
have typed those names, and changing it churns every install for a defect that
a fail-closed rule addresses. What changes is what happens when it is not
injective:

1. Two constituents mapping to the same catalog plugin name is a **collision**.
2. On collision **neither** entry is published. First-in-order wins is removed.
3. A collision writes a ledger event naming both `(marketplace, plugin)` pairs
   and the catalog it occurred in, increments a metric, and appears on
   `GET /api/catalog`.
4. Registration **warns** when a proposed marketplace name is a prefix of, or is
   prefixed by, an existing one. It does not refuse: the declarative estate
   reconciler is additive and never destructive, and a refusal here would fail a
   deploy over a name that may never collide.
5. A duplicate plugin name **within** one marketplace is a manifest defect in
   that marketplace, reported against it, not silently de-duplicated.

Under filtering this stops being optional. A filtered catalog has a different
plugin set per audience, so an order-dependent winner is a *different* winner
per audience: the same install name would resolve to different publishers'
content for different teams. Filtering cannot be built on an ambiguous
namespace.

### 4. Every catalog revision's composition is persisted, keyed by its commit SHA

The commit message stays (it makes the catalog self-describing to a plain git
client, which is worth keeping). It stops being the only record. At synthesis
the gateway persists a row per catalog revision: catalog name, commit SHA,
timestamp, the ordered `(marketplace, snapshot SHA)` constituents, the
exclusions applied, and the collisions detected.

This is what makes "what did we serve this audience, and when" answer from the
gateway's own records after the commit has become unreachable — joining the
ledger's `sha` to an immutable description. It is a prerequisite for auditing
any filtered view, and it repairs the gap that already exists in the global
catalog.

It also gives the consumer-visible half: a filtered catalog **declares that it
is filtered**, in its commit message and on `GET /api/catalog`. No manifest
field is invented for it, because the manifest schema is upstream's and young
(architecture §14, open question 3); the git layer carries the statement
instead, where any consumer with facade access can already read it.

### 5. Entitlement, when it is built, is a facade deny — and it is not built here

A per-team catalog that only omits things is a **convenience**: it saves a team
from reading a manifest full of plugins it does not use. It is worth building on
its own terms and it must be described in exactly those terms.

An entitlement that *means* something — "team X may not obtain plugin Y" — is a
per-identity authorization decision on the serving surface. When it is built:

- it is evaluated **at facade repository resolution**, beside the existing scope
  check, and it applies to `/git/{marketplace}` as well as to any catalog. A
  deny that only covers catalogs is defeated by fetching the marketplace;
- it is over state the principal **cannot grant themselves**, which token scopes
  are not;
- a denied fetch answers **404**, matching the established convention that an
  out-of-scope fetch is indistinguishable from a marketplace that does not
  exist, so an entitlement list is not a directory of what else the gateway
  governs;
- team membership derives from the **existing** claim-mapping machinery
  (`skills-gateway.roles.claim` and its mappings), as a further mapping target —
  not a fifth identity source;
- teams, memberships, per-team catalogs and entitlements are API-managed runtime
  state and therefore extend `skills-gateway.estate.*` **in the same change**
  that introduces them, per the standing obligation from `#65` — joining
  `marketplaces`, `grants`, `webhooks`, `audit-sinks` and `policy-rules` as
  reconciled object types, additively and with no prune mode;
- it is a trust-boundary change under `CLAUDE.md`'s risk-scaled rule, so it
  carries adversarial and negative tests and an evidence report.

None of that is decided by this ADR beyond the constraints above, and none of it
should be built until an actual deployment has two audiences with different
entitlements. A per-identity deny with one team behind it is untested
machinery guarding nothing.

### 6. Multi-ref publication is not part of this issue, and it is bigger than it looks

The fourth bullet — publishing approved snapshots on more than one ref — is a
registration-and-promotion feature. Architecture §5 "Refs" already describes it:
promotion becomes per-`(upstream, ref)`, each vetted ref advances independently
through the same gate, unvetted refs do not exist on the facade. It touches
registration, ingestion, approval and the published repository's ref layout. It
does not touch catalogs, entitlements or filtering, and nothing in it is blocked
by or blocks them.

It is also not the small feature the issue's placement implies. The gateway
today refuses to be told which ref to ingest — `GW_INGEST_0006 — Gateway-pinned
ingestion ref` rejects a registration that supplies one, `DEFAULT_BRANCH` is a
constant, and `DeclaredMarketplace` deliberately has no `ref` field so the
declaration cannot express one. `CLAUDE.md` names the gateway-pinned ref as part
of the registration trust boundary. Multi-ref publication therefore does not
*extend* registration; it **re-decides a trust-boundary requirement**, and it
needs its own ADR and its own adversarial tests.

It is here only because #4's Phase 2 listed it next to virtual marketplaces.
**It should be split into its own issue** and sequenced on its own merits.

### 7. The resulting order

| # | Slice | Why here |
| --- | --- | --- |
| 1 | **Collision semantics + revision provenance** — items 3 and 4 above | Corrects a live substitution path in shipped code and makes a filtered view auditable. Adds no authorization surface, no new estate object, no new serving path. Everything after it depends on both. |
| 2 | **Per-plugin exclusions** — item 2 above | The issue's own "cheapest slice", and correct once (1) has landed. Delivers approving a marketplace while withholding one plugin, instead of rejecting the whole snapshot. Adds the sixth estate object type. Skill-level exclusion is a separate decision (item 2). |
| 3 | *(split out)* **Multi-ref publication** | Independent. Own issue, own sequencing. |
| 4 | **Per-team catalogs, as a convenience** — a named catalog over a chosen constituent and exclusion set | Reuses (1)–(2) entirely; adds no authorization. Buildable whenever a deployment wants curated views. |
| 5 | **Entitlement as a facade deny** — item 5 above | The only genuinely new trust boundary in #11. Gated on real multi-audience demand, and built as its own ADR-worthy change. |

Slice 1 is the recommended first change and the one this ADR's companion
OpenSpec proposal specifies.

## Consequences

- **A shipped feature is being called defective, in public, in a design
  document.** That is the point: the collision rule is documented today as a
  naming curiosity, and it is a substitution path. Recording it here is what
  makes fixing it a scheduled change rather than a footnote someone reads after
  an incident.
- **Fail-closed on collision loses a plugin that is served today.** In an estate
  where a collision already exists, slice 1 removes *both* entries where the
  status quo published one. That is intended — the published one may be the
  wrong one — but it is a behaviour change on the serving surface and it needs
  the collision to be visible on the API and the ledger before it is defensible,
  which is why the two ship together.
- **Exclusion will be asked to be a security control.** Someone will want to
  "hide" a plugin from a team and will assume hiding is denying. The
  documentation, the API vocabulary and the portal wording all have to keep
  saying otherwise, and the honest answer to "make this unreachable" stays
  *revoke it*.
- **Persisting revision composition adds a table and a write on every rebuild**,
  including the rebuild every approval and revocation triggers. It is small and
  append-only, and it comes under the existing retention story rather than
  inventing one.
- **Rebuild cost is `O(catalogs × marketplaces)` per publication change**, on one
  in-JVM `synchronized` lock with no cross-instance coordination. One global
  catalog hides this; a catalog per team does not. Before slice 4, rebuild needs
  to become incremental, or scheduled and coalesced, or both — and that is a
  named prerequisite rather than a surprise found in production.
- **Synthesis is currently filesystem-bound and multiplying it multiplies the
  problem.** `CatalogService.vendor` fetches from
  `published.getDirectory().getAbsolutePath()`, and a JGit `DfsRepository` — what
  the object-store backend hands back — has no directory. Whatever the correct
  fix, it is a **prerequisite of the per-team slice**, not an optional cleanup:
  one broken catalog under the object-store backend is a bug, and a broken
  catalog per team is the feature not working at all. This ADR records the
  dependency; the fix belongs to whatever change owns storage-backend fidelity.
- **ADR 0008 is reinforced, not strained.** Every catalog is another published
  repository behind the same facade; entitlement, when built, *narrows* what the
  facade serves and does so where every fetch is already on the ledger. The
  breach to watch for is any proposal to expose a filtered view somewhere other
  than a published repository behind the facade.
- **If entitlement is ever built as a token scope, this ADR has to be
  superseded.** That would make an authorization control self-granted, and it
  should cost an ADR rather than a code review.

## What would reopen this

- A deployment with two audiences that genuinely must not see each other's
  plugins — that is the trigger for slice 5, and it is a product decision, not
  an engineering one.
- Upstream marketplace manifests gaining a namespace or scoping construct, which
  would make the concatenation defect moot and might justify changing the naming
  scheme after all.
- Evidence that consumers do read the catalog's git history — which would raise
  the commit message from "self-describing nicety" to a contract, and change what
  may be written there.

# ADR 0014 — Estate export: content and its attestations leave together; an import can only fill quarantine

*Proposed, 2026-09-08. Answers the questions posed by
[#152](https://github.com/skillsgateway/skillsgateway/issues/152); extends the
backend-to-backend movement decided under `pluggable-git-storage`.*

## Context

The gateway's value proposition is that it is the **only door** to a set of
approved skills. Everything else in the product follows from that: the facade
serves nothing that `ApprovalService` did not publish, quarantine is never
exposed, and every fetch lands on the ledger.

That is also the shape of a lock-in. An estate that can only be read by the
thing that wrote it is an estate whose owner has no exit — and an organisation
that suspects it has no exit will hesitate before putting its entire skill
supply chain behind one door.

### What exists today

`StorageMigration` moves every repository from one storage backend to another
*within one gateway*. Its most valuable property is not the copy: it is that it
re-reads **both** sides independently afterwards and compares resolved reference
sets and symbolic links, then refuses to report success on any disagreement. The
source is opened and never written, so the operation is reversible by
construction. That is the standard this ADR inherits.

What it does not cover is getting an estate **out of** a Skills Gateway and into
something else: another instance, another organisation's instance, a bare git
host, or an archive that outlives the product.

### What the exit looks like now

Today an operator who wants their content out has to talk to whichever storage
backend is in use, in that backend's own terms, and then reconstruct by hand the
database state that gives those repositories their meaning.

On the `filesystem` backend the first half is at least tractable — the volume
holds bare repositories a plain `git clone` can read. On the `object-store`
backend it is not: the bucket holds **JGit DFS packs described by a
`RepositoryManifest`**, and no git client on earth can clone a directory of
those. The backend most likely to be used in production is the one with no
plain-git exit at all.

The second half is worse either way, because **the repositories are not the
estate**. A bucket or a data directory holds git objects. What makes a given SHA
*approved* lives in PostgreSQL, across eighteen tables:

| What it establishes | Where it lives |
| --- | --- |
| That a marketplace is registered, at which upstream URL, under which sync mode and push policy | `marketplaces` |
| That a SHA is a snapshot of a specific upstream commit, and its state — `held`, `approved`, `rejected`, `revoked` | `snapshots` |
| Who ingested it and who decided it — the two identities the four-eyes rule compares | `snapshots.ingested_by`, `snapshots.decided_by` |
| That it was revoked, when, and by whom | `snapshots.revoked_at`, `revoked_by` |
| That its content was reclaimed under retention, when, and why | `snapshots.deleted_at`, `deleted_reason`, `purge_after` |
| What the vetting chain found, connector by connector, finding by finding | `vetting_runs`, `vetting_verdicts`, `vetting_findings` |
| Which findings were waived, by whom, on what justification, until when | `vetting_waivers` |
| That an administrator overrode a blocked outcome, and on what stated reason | `snapshot_vetting_overrides` |
| Which external plugin sources a composite snapshot grafted, and at which resolved SHAs | `snapshot_closures`, `snapshot_closure_members` |
| Every fetch and every administrative action | `fetch_log` |

Copying the git side alone produces content nobody can attest to. Copying the
database alone produces attestations pointing at content that is gone. **An
export that separates them is not an export**, and neither half is a product
capability today.

Two further facts about that table shape matter later and are recorded here.
**Retention eventually removes the rows themselves**: soft delete keeps the
snapshot and its state until `purge_after`, but compaction deletes the row and
cascades `snapshot_closures`, `vetting_runs`, `vetting_verdicts`,
`vetting_findings` and `snapshot_vetting_overrides` away with it. The ledger is
never pruned, so **`fetch_log` is the only permanent record of an estate's
history** — which is exactly why an archive that omits it is not an archive.
And **the ledger is not hash-chained**; its append-only property is a discipline
of the code and of the database controls, not tamper-evidence. A ledger copy in
a file inherits that limit rather than escaping it.

## The asymmetry that decides the shape

Export and import look like two directions of one feature. They are not. They
are two features that happen to share a file format, and they fail in opposite
ways:

- **Export is a disclosure problem.** The question is *what must not leave*. Get
  it wrong and a credential, a webhook secret, or a set of role grants walks out
  in a tarball.
- **Import is an authorization problem.** The question is *what may be
  asserted*. Get it wrong and a JSON file becomes a way to mark content
  approved — which is to say, a way to put arbitrary content on the facade
  without `ApprovalService` ever running.

The second is the single most important constraint on this feature. **An import
that could mark content approved would bypass the approval gate entirely**, and
preventing exactly that is the reason this system exists. Anything imported is
untrusted by definition, whatever the artefact claims about itself, because the
artefact is a file and a file has no provenance a running gateway can check.

## Decision

### 1. One format, two selections — not two formats

The unit is a **selection over a single artefact format**, not a choice between
two products:

- **Marketplace scope** — the useful unit for handing content to someone else.
  Published content plus the attestations that explain it. Nothing estate-wide.
- **Estate scope** — the useful unit for archival. Every marketplace, plus the
  estate-level objects that are safe to carry (policy rules, connector
  configuration, a ledger copy), plus quarantine content so that "prove what we
  rejected" has something to point at.

The manifest states its own scope, and **enumerates every category it
deliberately excluded**. A reader must never have to distinguish "this export
has no policy rules because it is marketplace-scoped" from "this export lost its
policy rules" by inference. Absence is never silence.

Rejected: marketplace-only (an archive of a whole estate is a real need, and
assembling one from forty separate exports loses the estate-level objects
entirely) and estate-only (too coarse to hand a partner one marketplace, and it
would mean exporting content the recipient must not have).

### 2. The format is git bundles plus JSON, in a directory

```text
manifest.json                              export identity, scope, exclusions
checksums.txt                              sha256 per file
ledger.ndjson                              estate scope only; the existing export's own lines
marketplaces/<name>/
  registration.json                        the marketplace row, secrets removed
  published.bundle                         git bundle — clonable, verifiable
  published.json                           ref map in RepositoryManifest's vocabulary
  quarantine.bundle                        estate scope only
  quarantine.json
  snapshots.ndjson                         one line per snapshot, with its disposition
  vetting.ndjson                           runs, verdicts, findings
  waivers.ndjson                           waivers and overrides, as evidence
  closures.ndjson                          external plugin source closures
```

Three properties are load-bearing:

- **A plain git client reads it.** `git bundle verify published.bundle` checks
  it; `git clone published.bundle work` opens it. No gateway, no database, no
  product.
- **A text editor reads the rest.** JSON and NDJSON, in the **same vocabulary
  the object-store backend already uses** — `RepositoryManifest`'s `version`,
  `refs` map and `ref: ` symbolic prefix mean the same thing in the bucket and
  in the archive. NDJSON rather than one large document because the ledger of a
  real estate must stream, and because it is already the shape
  `GET /api/audit/export` produces: one spelling of a ledger line, not two.
- **It is backend-independent by construction.** The export reads through the
  existing `GitStorage` seam, so the `filesystem` and `object-store` backends
  produce comparable artefacts from the same estate. No new method on the seam.

Options considered:

| Option | Why not |
| --- | --- |
| A proprietary archive format | Reproduces the lock-in this is meant to remove. An artefact only the product can read is the problem, restated as a file. |
| A copy of the storage layout | Backend-specific, and on `object-store` unreadable by any git client. It is *what we have today* and it is why the issue exists. |
| `pg_dump` plus a storage copy | Honest, simple, and **retained** — see decision 7. It is a backup, not a portable artefact: unreadable without the product, not selectable per marketplace, and it carries every secret in the database. |
| A single bundle for the whole estate | Loses the per-marketplace unit, and a bundle that must be complete to be readable is a poor archive. |

### 3. Content and attestation are one artefact, structurally

There is no mode that produces bundles without manifests, and none that produces
manifests without bundles. This is a property of the layout rather than of the
documentation: the manifest is written before the export is reported complete,
and the verification pass (decision 6) fails an artefact whose attestations
reference a SHA no bundle contains.

The exception is deliberate and stated in the artefact itself — see decision 5.

Attribution is part of the attestation, not an optional field. A snapshot line
carries `ingested_by`, `decided_by` and `decided_at` — the two identities the
four-eyes gate compares and the moment the decision was made — and it carries
them for a revoked snapshot too, because `decided_by` is deliberately preserved
through a revocation: *who approved this* has to survive the retraction. An
export that dropped it, or that collapsed both identities into a single "actor",
would launder the separation-of-duties evidence that is the whole reason those
two columns are distinct.

### 4. Import lands in quarantine or nowhere, and the rule is about the *shape* of the state

Rather than a list of tables to be maintained by care, the rule is
**allow-shaped state never imports; deny-shaped state may.**

*Allow-shaped* state is state whose effect is to permit something. A wrong one
puts content on the wire.

- An approved snapshot imports as `held`. Always. There is no flag, no operator
  confirmation and no signature that changes this.
- Waivers do not import as waivers. A waiver is a local identity's decision to
  accept a specific finding; importing one imports a bypass.
- Vetting overrides do not import. Same reason, whole-outcome rather than scoped.
- Role grants do not import. A grant is an authorization decision about
  identities in *this* identity provider.
- Access tokens cannot import because they never export.

*Deny-shaped* state is state whose effect is to refuse something. A wrong one
costs an unnecessary review.

- A revoked snapshot imports as **revoked**, not held. This is not a
  convenience: a revocation that arrived as "held" would let the destination's
  reviewer approve, in good faith, content the source had already withdrawn.
- A rejection imports as `rejected`.
- Policy deny rules may import, disabled, for an operator to enable.

**Foreign attestations import as evidence, never as verdicts.** The source
instance's vetting verdicts and findings are visible to a reviewer deciding
whether to approve; they do not satisfy the local vetting gate, which runs its
own chain against the imported content. Every attestation is namespaced under
the instance that made it, so **there is no field in the format whose plain
reading is "this is approved here"** — a constraint on the export format even
though import is not being built, because the format is the part that is hard to
change later.

A registration imports as a *proposal* that still faces
`skills-gateway.allowed-url-schemes` and the gateway-pinned ref. Importing a
registration must not become a way to point the gateway at a URL scheme the
allowlist forbids.

There is already a mechanism with exactly these properties, and a future import
should be it rather than a second one. `EstateReconciler` is additive and
idempotent, never deletes, isolates per-entry failure, attributes its acts to
its own declared actor on the ledger, and — the part that matters here — routes
every declared marketplace through `MarketplaceRegistrationService`, so the
scheme allowlist and the gateway-pinned ref cannot be bypassed by declaring
around them. **An import should be the estate reconciler fed from an artefact
instead of from configuration.** A new write path that re-implements those
guarantees is how they end up diverging.

### 5. What is deliberately excluded

| What | Where it lives | Treatment |
| --- | --- | --- |
| Personal and machine access tokens | `access_tokens` | **Never exported.** A credential that survives an export is a credential nobody revoked. Not even the hash: it is a verifier, and it is offline-attackable. |
| The inbound webhook secret | `marketplaces.webhook_secret` | **Never exported.** This one is the trap — it hides inside the marketplace row, so "export the registration" leaks an HMAC key unless someone thought about it. |
| The outbound webhook / audit-sink signing secret | `webhook_subscribers.secret` | **Never exported.** |
| Storage, forge-mirror and IdP credentials | Configuration, not the database | Out of scope by construction; the export reads the database and the git seam, never the configuration. |
| Role grants | `role_grants` | Estate scope may carry a **report** of who held which role at export time, as evidence for an auditor. It is not importable, and it is labelled as a statement about another instance's identity provider. |
| Audit-sink cursors | `audit_sinks.cursor_position` | **Never exported.** A cursor is a fact about a receiver that belongs to a different instance. |
| The audit ledger | `fetch_log` | Estate scope carries a **copy**, as evidence, labelled with the instance that wrote it. |
| Configuration | Not in the database at all | Out of scope by construction. External vetting connectors, per-marketplace retention policy, the forge mirror, IdP claim mappings and the bootstrap admin list are configuration, and configuration holds credentials. An export is therefore **not a complete estate reconstruction**, and must say so: the operator's declarative estate file is their own artefact and travels by their own route. |
| The virtual catalog | `published/<catalog-name>` in storage, with no `marketplaces` row | **Not exported.** It is a projection of the approved estate that the gateway rebuilds; a stale copy at a destination would assert a catalogue that does not match what is served there. The export walker must skip it deliberately, because a walk over `GitStorage.marketplaces(PUBLISHED)` will otherwise pick it up as though it were a marketplace. |

On the ledger specifically: it is append-only and belongs to the instance that
wrote it. An import **must not graft another instance's history onto this one's**
— `fetch_log` is this gateway's own record of its own acts, and an entry in it
that this gateway did not perform makes every answer it gives unreliable. A
future import writes exactly **one** local ledger entry: an import happened, from
instance X, of artefact digest D, by principal P, at time T. The foreign ledger
stays an attached document under its own instance identity.

### 6. An export verifies itself, and can be verified without a gateway

`StorageMigration`'s discipline, inherited whole:

- **After writing**, the export re-opens each bundle **from the file it just
  wrote** and compares its resolved reference set and symbolic links against a
  fresh read of the source repository — the same comparison
  `StorageMigration.compare` performs, reused rather than re-implemented. It
  then re-reads every attestation file from disk and checks that each attested
  SHA is reachable in the bundle that is supposed to hold it.
- **Success is never the default outcome of having finished.** An export that
  does not verify is reported as *not complete*, naming what disagreed, in the
  shape of `StorageMigration.Report.refusal()`.
- **Offline**, a `verify` mode over an artefact directory opens no database and
  reads no configuration: checksums, `git bundle verify`, every attested SHA
  present, every referenced marketplace present, the exclusion list intact.
- **By hand**, the same checks are `sha256sum -c`, `git bundle verify` and `jq`.
  Documented, because an archive that outlives the product must be checkable
  after the product is gone.

Verification must distinguish **deliberately absent** from **missing**. An
estate that has run retention holds snapshot rows whose content was reclaimed:
`deleted_at` set, the attestation intact, the objects gone. That is a healthy
estate, and a verifier that treats it as a corrupt archive is a verifier nobody
will trust. So each snapshot line carries an explicit content *disposition* —
present, or soft-deleted under retention and restorable until `purge_after` —
and verification checks the artefact against what it claims rather than against
an assumption.

Past that window there is nothing to attest with. Compaction deletes the
snapshot row and cascades its vetting runs, verdicts, findings, overrides and
closure away; what survives is the ledger. So an estate export can say
*this snapshot existed and this is its evidence*, or it can say *the ledger
records a snapshot that this estate no longer holds evidence for* — and it must
be able to say the second, because on any estate with retention enabled that is
the majority of its history. An archive built only from `snapshots` would
silently present a truncated past as a complete one.

The corresponding hazard runs the other way, and it is the one worth stating
loudest: **the dangerous export is not the one that carries too much, it is the
one that carries too little.** An export that quietly omits revoked snapshots
reads, at the destination, as if they never existed — and a destination that
re-ingests the same upstream will approve them clean. Omission launders a
revocation. Revocations and rejections are therefore not filterable out of an
export.

### 7. No signature, and no claim to be a backup

`checksums.txt` detects corruption and casual tampering. It is **not** a
signature, and this ADR does not introduce one: signed provenance stays where
[ADR 0005](0005-signed-provenance-stays-phase-3.md) put it. An unsigned
attestation file is exactly as trustworthy as whoever handed you the directory —
which is, independently, another reason import cannot honour it.

And the export is **not a disaster-recovery restore**. The DR answer stays what
it already is: a database backup plus a storage copy, or `StorageMigration`
between backends. Documentation must say so, because a feature called "estate
export" will otherwise be used at three in the morning by someone who assumed it
was one.

The reason is not effort. It is decision 4: the one thing a restore needs to
bring back is approvals, and approvals are the one thing an import must never
write.

## Why the import half is not built yet

This is a recommendation to build **export first and import not at all yet**,
and the argument is worth stating rather than assuming.

**A round trip is lossy by design.** Every snapshot comes back held; every
approval has to be made again by a local identity through the local gate, with
four-eyes and cooling-off intact. For an estate of any size that is a real cost,
and it is the *correct* cost — but it means import's value is narrower than it
first appears. What it genuinely saves is re-fetching content from upstreams
that may no longer exist, and carrying evidence a reviewer wants to see. It does
not save the review, and it never will.

**The tempting shortcut is the dangerous one.** The obvious way to make a round
trip lossless is a self-import: sign the export with the instance's own key, and
on import into the same instance id, restore approvals. It is written out here
because a reader will think of it.

It is rejected. It creates a code path capable of writing `approved` from a file
— and once that path exists, the security of the approval gate depends on a
scope check inside it that a later refactor can loosen without anyone noticing.
Against that, it buys no capability: the operator who can present a
self-signed export is, by definition, the operator who can restore the database
directly, which requires no new code path at all. A bypass surface that grants
nothing new is a pure cost.

**And the product question is unanswered.** What a reviewer *sees* when
re-approving imported content — whose verdicts, presented how, distinguished
from local ones how — is a design decision that needs a real estate and a real
operator to get right. Guessing at it now would bake a format around a workflow
nobody has used.

So: **increment one is export plus offline verify.** Import gets its own change
and, if its trust rules ever need to move, its own ADR. The rules in decision 4
bind that future work now, so the format shipped by increment one cannot make
the unsafe import easy to write later.

## Consequences

- Export is implementable immediately, and is what the linked OpenSpec change
  proposes. It touches no trust boundary in the *inbound* sense; it is
  nonetheless a disclosure surface, so its exclusion list is enforced by
  negative tests rather than by review.
- The format is the durable commitment. Because import is deferred, the format
  must already be import-safe: instance-namespaced attestations, explicit
  dispositions, explicit exclusion lists, no field that reads as a local
  approval.
- `GitStorage` gains no method. The export reads through the seam as it stands,
  which is what makes it backend-independent and what keeps the object-store
  backend from acquiring a second, divergent notion of "what is in this
  repository".
- The documentation must state, in the guide and in the reference, that this is
  not a backup and that a round trip does not preserve approvals. A capability
  people misunderstand is worse than one they do not have.
- The estate export is a new place where quarantined — that is, unreviewed and
  possibly hostile — content leaves the gateway as a file. The artefact must say
  so on its face, and marketplace-scope exports default to published content
  only.
- **An export is an act, not estate state.** The declarative estate guide draws
  that line already — converge-able state is declared, acts and reads are
  called — so producing an export does not extend `skills-gateway.estate.*` and
  the continuous obligation in `CLAUDE.md` is satisfied by saying so rather than
  by adding an object type. A future *import*, being a reconciliation, sits on
  the other side of that line and is expected to extend it.

## Uncertainties, and what would settle them

Stated rather than smoothed over.

1. **Does `git bundle` round-trip everything the gateway puts in a repository?**
   Specifically symbolic `HEAD` and the `refs/snapshots/*` namespace. This is not
   a theoretical worry: `StorageMigration` already hit exactly this class of bug,
   where treating a symbolic reference as an ordinary one silently dereferenced
   it and grew a spurious `refs/heads/main` at the destination on any marketplace
   served from another branch. **Evidence that settles it:** an export of a
   published repository whose served branch is not `main`, verified by clone.
   If bundles cannot carry it, `published.json` becomes normative for HEAD and
   the offline procedure gains a step.
2. **How large is an estate-scope export in practice?** Quarantine accumulates
   every ingested snapshot that retention has not reclaimed. **Evidence:**
   produce one from a realistic estate and measure. If a per-repository bundle is
   unwieldy, the fallback is per-snapshot bundles at a cost in file count.
3. **Can a vetting finding contain matched secret material?**
   `vetting_findings.message` and `location` are connector-authored. If a
   secret-scanning connector quotes what it matched, exporting findings exports
   secrets. **Evidence:** read what each shipped connector writes into a finding.
   Note that this is a *pre-existing* disclosure question — those findings are
   already in the portal and the API — so the export inherits it rather than
   creating it, and the fix belongs upstream of the export if one is needed.
4. **Does an estate archive need quarantine *content*, or only quarantine
   *attestations*?** "Prove what we rejected, and why" may be satisfied by the
   verdicts and findings alone, at a large saving in size and a large reduction
   in hazard. **Evidence:** an actual audit request. Until there is one, estate
   scope carries the content and says loudly what it is.

## What would reopen this

- **Signed attestations landing** (an ADR 0005 pull-forward). A signed approval
  from a named peer instance is a different object from a JSON file, and a
  federation model could be designed on it. It would still not auto-approve —
  but "approved elsewhere, by this named operator, verifiably" becomes
  first-class evidence in front of a local reviewer, and that changes what import
  is worth.
- **Re-approval proving to be the real blocker** in a migration with a real
  estate. The answer then is a bulk re-approval workflow *inside* the gate —
  still four-eyes, still recorded, still a human decision — not an import that
  skips it.
- **A regulated requirement for a byte-exact restore including decisions.** That
  is a backup requirement. It should be answered with backup tooling and a
  documented restore procedure, not by weakening decision 4.

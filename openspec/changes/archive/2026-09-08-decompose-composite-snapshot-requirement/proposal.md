# Proposal: decompose-composite-snapshot-requirement

## Why

Issue [#299](https://github.com/skillsgateway/skillsgateway/issues/299) asks the
project to decompose one multi-behaviour requirement per change, following the
pattern PR #324 established for `GW_INGEST_0026` — *Resource-bounded source
resolution* — and PR #333 repeated for `GW_INGEST_0030` — *The resolved closure
is recorded as an immutable domain object of the snapshot*. `GW_INGEST_0024` —
*Deterministic composite snapshot with a gateway-local manifest* — was named as
a remaining candidate in both of those changes, and this change does exactly
that one requirement and nothing else.

`GW_INGEST_0024`'s single description enumerated seven distinct, independently
verifiable guarantees behind one id and one SVC:

1. the composite commit's tree holds the upstream content, the grafted
   external content, and a rewritten manifest whose sources are paths inside
   that commit;
2. the snapshot is identified by that synthesised commit;
3. the ingested upstream commit is the synthesised commit's parent, byte-exact
   and reachable;
4. the same upstream commit, resolved commits and rewrite implementation
   produce the same composite;
5. the commit itself records the upstream commit, each source and the rewrite
   implementation's identity;
6. a reserved-directory collision, a malformed plugin name, or a duplicate
   plugin name is refused with no commit synthesised;
7. the rewritten manifest is verified against the local-only manifest policy
   before the synthesised commit is used.

These clauses have different audiences and different failure modes. A reviewer
checking that a diff against the parent commit is trustworthy cares about 3; an
operator relying on re-ingestion of unchanged content being a no-op cares about
4; an auditor reading a commit with no access to the gateway's database cares
about 5. Each can regress independently of the rest, and one pass/fail verdict
across all seven cannot say which one broke.

## What Changes

**This change decomposes exactly one requirement and nothing else.**

- **`GW_INGEST_0024` keeps its id and gains seven children**,
  `GW_INGEST_0024.1` through `GW_INGEST_0024.7`, each linked to it by
  `references.requirement_ids`. The rule applied is **one child per
  independently-verifiable guarantee** — not one child per named policy
  violation: the three refusal conditions the original text enumerated (a
  reserved-directory collision, a malformed name, a duplicate name) stay
  together as a single child, `GW_INGEST_0024.6`, because they share one
  behaviour pattern — refuse, no commit synthesised — rather than being three.
- **The parent's own text becomes the ontological claim that is true across the
  whole set and is not any single child's**: the synthesised composite commit
  is the snapshot itself, not metadata kept beside a separate snapshot
  identity — the commit that is pinned, vetted, approved and served is that
  one commit, and every component at or behind a trust boundary reads the
  snapshot through it and through nothing else. That claim was previously
  stated only in the requirement's rationale; it is now the parent's
  description.
- **One parent SVC and seven child SVCs.** `SVC_GW_INGEST_0024` keeps its id
  and now verifies the cross-cutting claim — that the same composite commit is
  what is pinned at ingestion and what a client receives at the end, with
  nothing else standing in for the snapshot's identity anywhere in between;
  `SVC_GW_INGEST_0024.1` through `SVC_GW_INGEST_0024.7` each verify one
  guarantee.
- **Every annotation moves in the same commit.** Four `@Requirements`
  annotations move across `ManifestRewriter` and `IngestionService`; twenty-one
  `@SVCs` annotations move across `ManifestRewriterTests` and
  `ExternalSourceResolutionTests`. Prose mentions of the id in Java and SQL
  comments, and in the live `marketplace-ingestion` OpenSpec capability spec,
  move to the matching child.

Observable behaviour of the gateway is **unchanged**. No production code path,
no configuration key, no API response and no portal page changes. What the
change buys is that the traceability report now names which of the seven
guarantees regressed, rather than reporting one verdict over all of them.

## Capabilities

### Modified Capabilities

- `marketplace-ingestion`: `GW_INGEST_0024` — *The synthesised composite
  commit is the snapshot, not metadata beside it* — is now a parent with seven
  children. No behaviour delta.

## Out of scope

- **The other candidates from #299 are not touched.** Five unevaluated
  candidates remain (`GW_RELEASE_0003`, `GW_VETTING_0029`, `GW_APPROVAL_0004`,
  `GW_VETTING_0020`, `GW_AUTH_0015`), tracked separately; this change does not
  resolve #299.
- **No sweep, no tooling.** Nothing enforces the decomposition convention
  automatically; the reference is the shipped `reqstool-conventions` skill and
  the archived `reqstool-decomposition-conventions` change.

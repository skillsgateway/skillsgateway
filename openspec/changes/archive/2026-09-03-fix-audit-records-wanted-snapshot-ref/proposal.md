# Proposal: fix-audit-records-wanted-snapshot-ref

## Why

`AuditingPreUploadHook.onSendPack` in `facade/GitFacadeConfiguration.java` writes
the compile-time constant `SERVED_REF` — the literal `refs/heads/main` — into the
`ref` column of every `upload-pack` ledger entry, for every want, unconditionally:

```java
for (ObjectId want : wants) {
    auditHook.record(source, principal, marketplace, "upload-pack", SERVED_REF, want.name());
}
```

The facade advertises two namespaces (`GW_0134`), and both are legal wants. An
approved snapshot stays fetchable by name after a later approval has superseded
it — that is deliberate, documented behaviour, and `RefAdvertisementTests`
guards it. So a client that fetches `refs/snapshots/<sha>` of a *superseded*
snapshot leaves a ledger row asserting it fetched the marketplace's current tip.
The row is not merely imprecise: the ref it names is a reference the client did
not ask for and, in this case, one whose content the client did not receive
([#206](https://github.com/skillsgateway/skillsgateway/issues/206)).

That matters because the fetch ledger is an evidentiary surface. `GW_0008`
requires it to record the ref of every facade fetch, and a constant is not a
record. Two materially different requests — "give me whatever you serve now" and
"give me this specific snapshot, by name" — are today indistinguishable in it,
and the second is the one a leak trace or a superseded-content question is
actually about.

**Two claims in the issue do not survive contact with the code, and this change
states them rather than quietly fixing something that is not broken.**

- *"Adoption reporting attributes a by-SHA fetch of a superseded snapshot to the
  current tip."* It does not. `FetchLogRepository.adoptionSince`,
  `marketplaceAdoptionSince` and `latestFetchPerIdentity` all aggregate on the
  `sha` column and never read `ref`; `AdoptionService` marks a SHA current by
  comparing it against the resolved tip. The `sha` column already holds
  `want.name()`, which for a snapshot-ref want is that snapshot's own commit. So
  adoption and staleness are correct today and are not touched here. The defect
  is confined to one column of one event.
- *"`ref` is `null` for an `upload-pack` entry."* `docs/manual/reference/api/audit.md`
  says so in its example payload. It has never been true; the column has always
  held `refs/heads/main`. The example is corrected in the same change.

## What Changes

- **The `upload-pack` ledger entry names the advertised ref the want resolves
  to.** The hook maps each want `ObjectId` back over `UploadPack.getAdvertisedRefs()`
  — the same filtered set `SERVED_REFS` produced, so the ledger can never name a
  ref the facade did not advertise. A want matching a `refs/snapshots/<sha>` tip
  and nothing else records that ref; a want equal to `refs/heads/main`'s tip
  records `refs/heads/main`.
- **The tip wins the unavoidable ambiguity, and that is stated, not hidden.**
  While a snapshot is current, `refs/heads/main` and `refs/snapshots/<sha>` point
  at the same commit, and the git protocol puts only object ids in the want list
  — `PreUploadHook` is given `Collection<? extends ObjectId>` and JGit exposes no
  want-ref names to it. The two requests are therefore not distinguishable at
  this seam by any implementation, so the rule is deterministic and documented:
  the want equal to main's tip records `refs/heads/main`. The consequence is
  bounded and worth saying plainly — the ledger can under-report *which name* a
  client used for content it is currently serving, and can no longer misreport
  *which content* a client received.
- **A want matching no advertised ref records no ref rather than a wrong one.**
  Under the default `RequestPolicy.ADVERTISED` this is unreachable — every want
  is an advertised tip — but the fallback is the point of the change: the column
  says "unknown" instead of asserting the tip. `sha` still pins the content
  exactly.
- **`GW_0154`** in `docs/reqstool/` — the fetch ledger records the advertised ref
  a want resolves to — with `SVC_GW_0154` covering the superseded-snapshot case
  that is wrong today, the current-tip case that must not regress, and the
  deterministic resolution of the ambiguity.

Not breaking. No column is added, removed or retyped; no API shape, payload or
configuration key changes. Export consumers and audit sinks already receive
`ref` as a nullable string. The `info-refs` entry is untouched: an advertisement
genuinely is about the tip, and it resolves `refs/heads/main` to say so.

Existing rows are not rewritten. The ledger is append-only by design — no code
path in the product issues an `UPDATE` or `DELETE` against it — so historical
`upload-pack` rows keep saying `refs/heads/main`. The documentation says from
when the column became accurate.

## Capabilities

### New Capabilities

None. This adds a requirement to an existing capability.

### Modified Capabilities

- `git-facade`: `GW_0154` — the fetch ledger's `ref` for a pack transfer is the
  advertised ref the want resolves to, not a constant.

## Impact

**Code**

- `facade/GitFacadeConfiguration.java` — `AuditingPreUploadHook` resolves the
  want against the advertised set; the resolution is a package-private static
  function so the ambiguity rule and the no-match fallback are unit-testable
  without a git client.
- `docs/reqstool/requirements.yml`, `docs/reqstool/software_verification_cases.yml`

**Tests**

- A real-client test: approve A, approve B, fetch A by name, assert the ledger
  row names `refs/snapshots/A` — red before the fix.
- A real-client test that an ordinary clone still records `refs/heads/main`.
- Unit tests over the resolution function for the ambiguous and no-match cases.

**Behavior**

The observable change is one column value on one event, and only for a fetch of
a snapshot that is not the current tip. Nothing that reads the ledger changes
shape.

**Documentation**

- `docs/manual/reference/api/audit.md` — the `ref` field meaning, the
  `upload-pack` event row, and the wrong `"ref":null` example.
- `docs/manual/reference/git-facade.md` — the auditing section states what the
  `upload-pack` entry names and the tip-wins rule.
- `docs/manual/concepts/snapshots-and-ledger.md` — the `ref` row of the entry
  table.

**Explicitly out of scope**

- **Adoption attribution.** The issue asks whether adoption should attribute a
  by-SHA fetch to the snapshot named rather than to the marketplace tip. It
  already attributes by `sha`, which is the snapshot named; there is nothing to
  change and no requirement to add. Stated in the design so the question is
  closed by evidence rather than left open.
- **Backfilling historical rows.** The ledger is append-only; rewriting it to
  make old rows accurate would be a worse defect than the one being fixed.

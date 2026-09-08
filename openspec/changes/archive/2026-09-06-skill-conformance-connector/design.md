# Design: skill-conformance-connector

## Context

Three facts about the existing system shape everything below.

- **A connector is a Spring bean and nothing more.** Declaring one adds it to
  the chain; `VettingService` orders, times, records and aggregates. So the
  design question is not "how does it run" but "what does it say, and how much
  does what it says cost an operator who upgrades into it".
- **Verdicts are derived from severities, not chosen.** `Verdict.of` maps the
  worst severity present to a state: `HIGH`/`CRITICAL` → `FAIL`, `LOW`/`MEDIUM`
  → `WARN`, `INFO` alone → `PASS`. Picking a severity *is* picking whether a
  marketplace is blocked. There is no third lever.
- **The chain must be reproducible.** `GW_VETTING_0012 — Continuous re-vetting of
  approved snapshots` re-runs the chain over already-approved content and asks
  whether a changed answer came from the content or from the connector. That
  question is answerable only because `version()` is recorded per run and the
  rules cannot move without it moving.

## Goals / Non-Goals

**Goals**

- Say something true and actionable about every `SKILL.md` in the snapshot.
- Pin the specification being validated against, by date, in the repository, and
  name it in every verdict.
- Be safe to turn on: an operator upgrading into this connector must not find
  their estate blocked the next morning.
- Survive hostile frontmatter — an alias bomb, a nesting bomb, a megabyte of
  YAML — without hanging, exhausting memory, or throwing out of the connector.

**Non-Goals**

- Not a linter for the Markdown body. The specification places no restrictions
  on it beyond advice, and advice is not a finding.
- Not a semantic judgement about description quality. "Describes what the skill
  does *and when to use it*" is exactly the kind of thing this connector must
  not pretend to measure; it checks presence and length.
- Not a live conformance service. Nothing here fetches, caches or refreshes a
  remote schema.

## Decisions

### 1. The specification is vendored, dated, and transcribed by hand

`src/main/resources/vetting/agentskills-2026-08-04.json` is a transcription of
the frontmatter table and per-field rules of
<https://agentskills.io/specification>, taken from the upstream source
`docs/specification.mdx` at commit `217be548739f21d6008915c29aefe320ea1a90af`
(2026-08-04, the last commit to touch that file), cross-checked against the
upstream reference validator `skills-ref/src/skills_ref/validator.py` at commit
`547831f3a23724ba64a9b79bbf59c5e0bc8f2d1a`. Pinned on 2026-09-06. The file
carries all of that in a `source` object, and `vetting/README.md` beside it says
how to bump it.

**Upstream publishes no version identifier.** There are no tags on the
repository, no `version:` in the specification page and no changelog. So the
version this gateway records is *this repository's* dated pin — the date of the
upstream commit the transcription came from — and not a number upstream would
recognise. That is a deliberate consequence of the upstream state and is
documented as such rather than dressed up as a spec version.

*Alternative rejected — fetch the schema at vet time.* It makes a chain run
depend on the network and on a document that can change between two runs over
identical content, which destroys both reproducibility and the attribution
`GW_VETTING_0012` exists to provide. It also puts a remote document inside the approval
gate, which is a supply-chain surface the gateway spends the rest of its design
closing.

*Alternative rejected — a JSON Schema document validated by a library.* The
Agent Skills constraints are not expressible as clean JSON Schema without
inventing extensions (the `name`/directory match is relational; "lowercase"
under NFKC is not a `pattern`), it would add a dependency to validate a
six-field mapping, and a hand-rolled interpreter of a small declarative table is
easier to audit than a schema plus an engine. The vendored file is a
constraint table the connector reads, and the fields it holds are exactly the
fields the specification lists.

### 2. Advisory by default, blocking only on request

| Rule id | Raised when | Default | `enforce: true` |
| --- | --- | --- | --- |
| `skill-frontmatter-missing` | the file does not open with a `---` frontmatter block | `MEDIUM` | `HIGH` |
| `skill-frontmatter-malformed` | the block is never closed, is not valid YAML, or is not a mapping | `MEDIUM` | `HIGH` |
| `skill-field-missing` | a required field (`name`, `description`) is absent | `MEDIUM` | `HIGH` |
| `skill-field-invalid` | a field is present and violates its constraint | `MEDIUM` | `HIGH` |
| `skill-not-scanned` | the `SKILL.md` was over the size cap or not valid UTF-8 | `INFO` | `HIGH` |
| `skill-field-unknown` | a frontmatter key the pinned specification does not define | `INFO` | `INFO` |

The default posture is the one an operator gets by upgrading, and it must be
defensible without them having read this document. Three reasons it is advisory:

1. **A conformance defect is not a danger.** The chain's blocking states are the
   gateway's statement that content is unsafe to serve. A missing `description`
   makes a skill useless, not hostile, and conflating the two devalues every
   `FAIL` the reviewer sees.
2. **The blast radius is the whole marketplace, not the skill.** A verdict is
   per snapshot. One malformed skill out of two hundred would block the other
   hundred and ninety-nine — and the gateway's own approval gate offers no
   partial approval, so the only way out would be a waiver per finding.
3. **It matches how this gateway ships every other opinion.** `license-scan`
   blocks nothing until an operator configures a list; re-vetting warns until an
   operator chooses `enforce`. "An upgrade blocks nothing" is a property of the
   product, and a connector that broke it would be the exception.

`enforce: true` is for the organisation that has decided conformance is a
publishing requirement. Under it the connector is fail-closed in the strong
sense, which is why `skill-not-scanned` — a `SKILL.md` the connector could not
read — rises with the rest: under enforcement "we could not check" must not read
as "it conformed". Under the default posture it stays `INFO`, matching the
`file-not-scanned` convention the other connectors use for the same fact.

`skill-field-unknown` never rises. The pinned specification is a snapshot of a
moving document; a field added upstream next month is a field this gateway has
not heard of yet, and blocking on it would make every specification bump a
gateway outage. The specification also defines `metadata` precisely so clients
can carry properties it does not define, so an unknown *top-level* key is worth
one informational line and nothing more.

### 3. `version()` records the specification, the schema bytes, and the posture

```
agentskills-2026-08-04+schema-2ae36a+advisory
```

The first component is the dated pin. The second is the first six hex
characters of a SHA-256 over the canonicalised constraint table, so editing the vendored resource
without moving the date still moves the recorded chain identity — the failure
mode a hand-maintained vendored file actually has. The third is the posture,
because the same content legitimately yields a different verdict under
`enforce`, and a reader of two runs deserves to see which one they are looking
at. This mirrors `license-scan`, whose version carries a digest of the policy in
force for exactly the same reason.

### 4. One frontmatter parser, reused rather than copied

`SkillFrontmatter` already extracts and parses the frontmatter block, and it
already does it against hostile input. Duplicating it would give the gateway two
answers to "what does this file declare" — and the policy gate's answer is the
one that decides denials.

The seam is a new non-throwing entry point:

```java
public sealed interface Parsed permits Parsed.Fields, Parsed.Absent, Parsed.Malformed { }
```

`parse` returns which of the three a document is; `tools(...)` switches on it and
raises `PolicyEvaluationException` for `Malformed` exactly where it raised
before, returns `List.of()` for `Absent` exactly where it did before, and reads
`allowed-tools` out of `Fields` with the same list/comma-string/scalar rules. The
existing `SkillFrontmatterTests` are the standing evidence that nothing moved:
they are unchanged, and they pin every branch — absent, present-without-tools,
list, comma string with parentheses, malformed YAML, unterminated block,
non-scalar tools value.

The connector consumes `Parsed` and turns `Absent` and `Malformed` into findings
instead of exceptions, which is the whole reason a non-throwing entry point
exists.

*Alternative rejected — a second parser in the vetting package.* It removes the
risk to the policy gate by construction, and buys it with two implementations of
frontmatter extraction that will disagree the first time one of them is fixed.
The gateway would then report a skill conformant that the policy gate refuses to
read, which is a worse failure than the one being avoided.

### 5. The YAML loader's limits are set explicitly, not inherited

The frontmatter of a quarantined `SKILL.md` is attacker-supplied, and SnakeYAML
with default options is the difference between a finding and a denial of
service. `SkillFrontmatter` now constructs its `LoaderOptions` with every
relevant limit stated:

| Limit | Value | Why this value |
| --- | --- | --- |
| `maxAliasesForCollections` | 50 | Refuses the "billion laughs" alias bomb; SnakeYAML's own default, restated so an upgrade cannot raise it silently |
| `nestingDepthLimit` | 50 | Refuses a nesting bomb; likewise restated |
| `codePointLimit` | 1 MiB | Bounds the document before parsing |

`SafeConstructor` (already in place) is what keeps arbitrary object construction
off the table; these three keep a well-formed but abusive document from being
expensive.

The `codePointLimit` is the only one that differs from SnakeYAML's default
(3 MiB), and it is behaviour-neutral for the policy gate by construction:
`SnapshotFactsService` already refuses a `SKILL.md` over 256 KiB before parsing
it, so no document the policy path can reach is within a factor of four of the
new bound. On the vetting path the walk's `max-file-bytes` (1 MiB by default)
does the same job one layer out. Restating the other two at their current
defaults is not a behaviour change today; it is insurance against a SnakeYAML
upgrade quietly relaxing them under a security-relevant parser.

These are claims about a library, so they are tested as claims: the tests build
an alias bomb, a 200-deep nesting bomb and an oversize document and assert that
each is refused as a finding rather than parsed, and they assert it through the
connector, which is where the guarantee has to hold.

### 6. Discovery reuses the manifest, and the connector's path selection matches it

Skills are `<plugin source>/skills/<name>/SKILL.md`, which is the shape
`SnapshotContentService` already walks. The connector cannot call that service —
it is handed a `SnapshotUnderVetting`, not a repository — so it applies the same
shape as its `walk` selection: a path is wanted when it ends in `/SKILL.md` and
its parent's parent segment is `skills`. That keeps `GW_VETTING_0030 — Bounded
single-pass snapshot content access for a vetting chain run` honest: no other
blob is opened on this connector's behalf.

The selection is deliberately *shape*-based and not manifest-based. A skill
directory the manifest happens not to declare is still content the snapshot
ships, and a connector that consulted the manifest would let an upstream hide a
skill from conformance by omitting its plugin — the same reasoning that makes
`secret-scan` read the whole tree rather than the declared parts of it.

The skill's directory name — the thing `name` must equal — is the last path
segment before `SKILL.md`, so the relational rule is checkable from the path
alone.

### 7. A snapshot with no skills passes, and says so

An empty answer is the one a coverage requirement cares about most. The verdict
is a `PASS` whose summary states "scanned 0 SKILL.md file(s)", so
`GW_VETTING_0023 — A clean vetting pass records what it examined` keeps a snapshot with
no skills distinguishable in the ledger from a connector that did not run. The
connector does *not* raise a finding for a marketplace with no skills: whether
an empty marketplace is acceptable is a policy question, and the CEL policy
rules are where policy questions are answered.

## Risks / Trade-offs

- **The vendored specification will go stale.** Upstream has no version and no
  changelog, so nothing tells the gateway it has drifted. Mitigated by the date
  in the filename, the provenance block in the file, and the digest in the
  recorded version — a reviewer reading a run can always see which pin produced
  it. Bumping it is a deliberate PR that changes the recorded chain identity for
  the whole estate, which is the correct amount of ceremony.
- **The transcription is hand-made.** The vendored file was written from the
  published table and cross-checked against the upstream reference validator,
  not generated from a machine-readable artefact — upstream publishes none. A
  transcription error would produce a wrong finding, which under the default
  posture is a warning a reviewer can dismiss and under `enforce` is a block a
  waiver clears.
- **`enforce: true` can block a whole estate.** That is what it is for, and the
  documentation says so plainly next to the switch. The escape is the standard
  one — a scoped, expiring waiver per finding — and the on-ramp is that the
  default posture shows the operator every finding they would be enforcing
  before they turn it on.
- **`name` must equal the directory, and the specification's rule is Unicode
  NFKC-normalised.** The connector normalises both sides before comparing, which
  means two visually identical names in different normal forms compare equal —
  the upstream validator's behaviour, and the one a client will follow.

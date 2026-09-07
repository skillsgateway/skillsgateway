# Vendored Agent Skills specification

`agentskills-2026-08-04.json` is the constraint table the `skill-conformance`
vetting connector validates every `SKILL.md` against.

## Where it came from

| | |
| --- | --- |
| Specification | <https://agentskills.io/specification> |
| Upstream source | `docs/specification.mdx` in <https://github.com/agentskills/agentskills>, commit `217be548739f21d6008915c29aefe320ea1a90af` (2026-08-04) |
| Cross-checked against | `skills-ref/src/skills_ref/validator.py`, commit `547831f3a23724ba64a9b79bbf59c5e0bc8f2d1a` |
| Pinned on | 2026-09-06 |

Upstream publishes no version identifier, no tags and no changelog, and no
machine-readable schema. The table was therefore transcribed by hand from the
published field table and the per-field rules, and checked field by field
against the upstream reference validator. The `version` in the file is this
repository's dated pin — the date of the upstream commit transcribed from — and
not a version upstream would recognise.

Two deliberate departures from the prose, both following the reference
validator, which is the executable statement of the same specification:

- **`allowed-tools`** is described as a space-separated string. The reference
  validator does not check its shape at all, and YAML lists are widespread in
  published skills, so the connector accepts a string or a list of scalars and
  checks nothing else.
- **A field the table does not list** is an error in the reference validator.
  The connector reports it informationally instead: the specification defines
  `metadata` precisely so clients can carry properties it does not define, and
  a pinned copy of a moving document must not turn next month's new field into
  this month's blocked marketplace.

## Bumping the pin

1. Re-read the specification page and the reference validator, and note the
   upstream commit of each.
2. Add a **new** file named for the new date; do not edit this one in place.
   The filename is part of the version the gateway records against every chain
   run, and a run has to stay attributable to the bytes that produced it.
3. Point `SkillSpec.RESOURCE` at the new file and update this page.
4. Expect every marketplace's next chain run to carry a new connector version.
   That is the point: it is what makes a changed verdict about unchanged content
   attributable to the specification bump.

The connector never fetches any of this at vet time. A chain run must be
reproducible from the repository alone.

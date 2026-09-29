# ADR 0021 — Risk is expressed as findings, not tiers

*Accepted, 2026-09-29.*

## Context

`architecture.md` §6 designed three risk tiers, computed from the manifest and
never self-declared:

| Tier | Contents |
| --- | --- |
| T0 | `SKILL.md` and reference docs only |
| T1 | Skills bundling scripts the agent may run |
| T2 | Plugins registering hooks, MCP servers, or commands that execute code |

Each tier was to carry its own review depth and update policy. T0 in
particular was to auto-approve on a clean scan and auto-promote after a
cooling-off window. A snapshot moving up a tier was to be a review trigger.
The architecture page and the site's front page still describe tiers as
designed but not built.

The code went another way. The vetter chain emits **findings** with a
severity, and severity alone decides the outcome: medium warns, high blocks.
A finding that names a file is keyed by the git blob it was found in, so it
groups and is waived per content, in one commit
([vetting concept page](../manual/concepts/vetting.md)). The signals the tiers
were meant to carry already arrive this way:

- **T2's signal** is `executable-surface`: `auto-run-hook` for every hook,
  `mcp-package-run` and `mcp-fetch-exec` for MCP servers, and the
  runtime-fetch rules for the code they launch.
- **T1's signal** is the same vetter reading skill scripts. OpenSpec change
  `vetting-runtime-dependencies-and-lsp` is extending it to runtime
  dependencies, LSP servers, and download-and-execute in skill files.
- **T0's premise does not hold.** "Markdown only" is not "nothing runs": a
  `SKILL.md` can tell the agent to pipe a download into a shell, which is why
  `prompt-injection` has a `pipe-to-shell` rule.

The question this ADR answers is whether to build the tier model on top of
the findings anyway.

## Options considered

### Option A — Build the tiers as designed

Compute T0–T2 from the inventory and attach review depth and update policy to
each. Rejected:

- A tier is a lossy summary of findings the reviewer already sees. It adds a
  second vocabulary that can disagree with the first.
- The risks do not form a scale. A remote MCP server sends data out; a hook
  runs code in. Neither is "higher" than the other, so any ordering is
  arbitrary.
- T0's reward, auto-approval, is parked on its own merits: it would delegate
  the human gate, which is a product decision (§4, Policy engine). Without it,
  T0 changes nothing a reviewer does.

### Option B — A set of derived flags per snapshot instead of one tier

"Has hooks", "has MCP servers", "installs dependencies", and so on. Rejected
for the same reason as A: each flag restates a finding that already exists,
and it adds a portal concept with no mechanical backing.

### Option C — Findings are the risk model (chosen)

Keep severity as the only grade. Close gaps in what the gateway sees by adding
rules to the chain, not categories beside it.

## Decision

Risk is expressed as findings with a severity, grouped by blob and waived per
group. The gateway computes no tier, category or risk class for a snapshot or
a marketplace. A new kind of risk is a new rule in a vetter, with a severity
argued in its OpenSpec change.

The one tier idea that survives — *a change in what a plugin runs is itself a
review trigger* — is already delivered without tiers:

- every update is held until approved, since nothing auto-promotes;
- the review card's **Diff** tab shows added and changed plugins and skills,
  and its **Inventory** tab shows every hook and MCP server;
- a new hook or script is a new blob, so its finding falls outside every
  existing group waiver and has to be judged afresh.

## Consequences

- `architecture.md` §6 describes findings, not tiers. The tier references
  elsewhere on the site are removed.
- Review effort still scales with risk, as the architecture's principle
  requires, through severity: a Markdown-only snapshot with no findings needs
  a read; a hook that fetches code blocks until someone waives it.
- A per-tier policy ("which vetters are required per tier", "per-tier minimum
  release age") has nothing to attach to. An organisation that wants to refuse
  a kind of content outright writes a policy deny rule over the snapshot's
  facts (files, plugins, skills), as it can today.

## What would reopen this

Auto-approval being decided in favour. An auto-approve path needs an
eligibility predicate, and "no findings above low in these vetters" is the
first thing to try. A tier would be reconsidered only if that predicate proves
unworkable.

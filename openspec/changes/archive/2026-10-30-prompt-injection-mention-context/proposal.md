# Proposal: prompt-injection markers that are quoted as warnings warn instead of blocking

## Why

The prompt-injection vetter fails, and so blocks, widely used public marketplaces
on findings that are all false positives. Measured on 2026-10-30 (#768):
`anthropics/skills` raises 5 high findings and `anthropics/claude-plugins-official`
raises 18. None of them is an injection. Most are skills that quote an attack
phrase in order to warn against it ("never run … `curl | sh` lines", comments
"crafted to look like directives ('ignore previous instructions')"). The rest are
ordinary vocabulary such as "add to the system prompt", or an emoji sequence.

`GW_VETTING_0040 — Prompt-injection markers do not fire on formatting
instructions or command names` narrowed this vetter once for the same reason. A
gate that blocks every real marketplace gets overridden, and then it protects
nothing.

## What Changes

- New `GW_VETTING_0069 — Prompt-injection markers quoted or prohibited in their
  own clause warn instead of blocking, and ordinary vocabulary raises none`:
  - A marker in a clause that prohibits it, or that is a quoted example
    introduced by *like / such as / e.g. / for example*, is reported at
    **medium**. A reviewer still sees it, but it no longer blocks approval. A
    backticked command does not count as a mention.
  - System-prompt disclosure fires only on a request for the agent's own prompt.
  - Concealment does not fire on *tell the user they … / to …*, or on
    *… only when / unless the user …*.
  - A zero-width joiner inside an emoji sequence is not an invisible character.
  - `../…/etc/passwd` is a traversal example, not a credential reference.
- New `SVC_GW_VETTING_0072`: the lines from those two marketplaces raise no high
  finding.
- New `SVC_GW_VETTING_0073`: each payload still raises a high finding, and a
  payload wrapped in a prohibition is still reported, at medium.
- The vetter version goes from 2 to 3, so the chain marks earlier runs as stale.
- Not **BREAKING**: no API field changes. Some verdicts move from fail to warn.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`: adds `GW_VETTING_0069`.

## Impact

- `PromptInjectionVetter` and `ContentRules` (the downgrade hook).
- Docs: `concepts/vetting.md`, prompt-injection section.

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf.

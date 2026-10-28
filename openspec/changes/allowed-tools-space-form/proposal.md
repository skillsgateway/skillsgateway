# Proposal: `allowed-tools` splits on whitespace as well as commas

## Why

The Agent Skills specification writes `allowed-tools` as a space-separated
string (`Read Bash(git:*)`). The policy facts split that string on top-level
commas only, so the space form becomes one tool named `Read Bash(git:*)`. A
rule written the documented way, `t.startsWith("Bash")`, then never sees
`Bash`.

## What Changes

- `skills[].tools` splits a string declaration on top-level commas **and**
  top-level whitespace. Spaces inside parentheses, as in `Bash(git add:*)`,
  still belong to the tool. A YAML-list entry is split the same way, so neither
  form can carry two tools in one entry.
- `GW_APPROVAL_0007 — Fail-closed CEL policy gate at approval` already requires
  each skill's declared tools to be parsed from the frontmatter, so its text is
  unchanged. New `SVC_GW_APPROVAL_0031` verifies the space-separated form.
- Not **BREAKING**: no API, schema or configuration change. A rule could
  previously match a joined string such as `"Read Bash(git:*)"`; such a rule
  now sees the separate tools.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `policy-rules`: `GW_APPROVAL_0007` gains `SVC_GW_APPROVAL_0031`.

## Impact

- `SkillFrontmatter.tools`.
- Docs: `guides/policy-rules.md` (the `skills[].tools` paragraph).

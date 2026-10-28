# Design: report-unreadable-component-files

## Context

See proposal.md, Why. `PluginComponents.Reader.json(path, hookBearing)`
recorded a `Problem` only when `hookBearing`. That flag was false for every MCP
and LSP read, because the reader predates server vetting. `frontmatterHooks`
returned quietly on a file it could not read. The vetter turns every `Problem`
into `hook-config-unreadable`.

## Decisions

### D1 — Name what was not read, report always

The flag becomes the noun the message uses (`hooks`, `MCP servers`,
`LSP servers`). Every caller can declare something Claude Code runs, so there
is no caller left for which silence is right.

### D2 — An oversized skill or agent file is reported even without hooks

Without reading the file, the reader cannot tell whether its frontmatter
declares hooks. A Markdown instruction file over 1 MiB is unusual enough that a
medium finding is cheap, and passing over it says something the vetter does
not know.

### D3 — Severity stays medium

`GW_VETTING_0049 — Hook declarations and launched files the vetter cannot read
are reported` deliberately warns rather than blocks. Whether an unreadable
executable declaration should block is a separate, chain-wide decision. Here
the finding is made consistent across file kinds.

## Risks / Trade-offs

- [Snapshots gain warnings on re-vetting] → That is the intent. Existing
  waivers apply by fingerprint.

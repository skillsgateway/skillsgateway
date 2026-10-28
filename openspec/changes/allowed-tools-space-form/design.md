# Design: allowed-tools-space-form

## Context

See proposal.md, Why. `SkillFrontmatter.splitOutsideParentheses` tracks
parenthesis depth and treats only `,` at depth 0 as a separator.

## Decisions

### D1 — Whitespace at depth 0 is a separator

Commas and whitespace outside parentheses both separate tools, and neither does
inside them. So `Bash(git add:*, git commit:*), Read` and `Read Bash(git add:*)`
each give one fact per tool.

Alternative: fail closed on top-level whitespace by raising
`PolicyEvaluationException`. Rejected, because the space form is the
specification's own, and failing every approval of a conformant skill would be
a denial nobody could fix.

### D2 — YAML-list entries are split too

A list entry such as `- Read Bash(curl:*)` is not a form the specification
uses. Splitting it errs towards more facts, and a deny rule then fires on any
tool the entry names. Keeping it as one opaque string is the shape this change
removes.

## Risks / Trade-offs

- [A rule that matched the joined string stops matching] → Such a rule matched
  only by accident of the old parser. The documented examples all match per
  tool.

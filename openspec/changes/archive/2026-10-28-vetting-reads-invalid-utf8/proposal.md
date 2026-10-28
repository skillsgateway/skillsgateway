# Proposal: only a NUL byte makes a file binary to the vetters

## Why

The built-in vetters, and the hook-module reader, treat "not strict UTF-8" as
"binary" and skip the file. The consumers of these files do not draw that line:
- a shell runs a script with an invalid byte in a comment;
- Node loads such a module;
- an agent reads such a Markdown file.

So one stray byte takes the file out of every pattern rule, while the client
still runs or reads it.

## What Changes

- A file is binary for scanning only when it holds a NUL byte. Any other file
  is decoded as UTF-8 with replacement characters and scanned. This applies to
  every built-in vetter, the bundle sent to external vetters, and the
  hook-module import reader.
- The "not scanned" finding and coverage-summary text says "binary" instead of
  "not valid UTF-8", which no longer describes what was skipped.
- `GW_VETTING_0043 — Unscanned files are reported once per reason and stated in
  the coverage summary` already says "not readable as text", so its text is
  unchanged. New `SVC_GW_VETTING_0063` verifies that a stray byte changes no
  verdict.
- Not **BREAKING**: no API, schema or configuration change. Files that were
  skipped as binary but hold no NUL byte are now scanned, so a snapshot can
  gain findings it did not have.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`: `GW_VETTING_0043` gains `SVC_GW_VETTING_0063`.

## Impact

- `ContentRules.text`, `HookModules.text`, and the messages of
  `PromptInjectionVetter` and `SkillConformanceVetter`.
- Docs: `concepts/vetting.md`, `guides/adding-an-external-vetter.md`.

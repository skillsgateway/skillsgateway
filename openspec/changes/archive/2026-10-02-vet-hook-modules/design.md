# Design: vet-hook-modules

## Context

Motivation: see proposal.md. Current state:

- `PluginComponents` is the one reading of the plugin layout, shared by the
  inventory (`SnapshotContentService`) and `ExecutableSurfaceVetter`.
  `Reader.hooks()` accepts `{"hooks": {Event: [...]}}` or the bare event map and
  silently skips any value that is not an array of matcher groups. Problems it
  returns (`hookProblems`) become `hook-config-unreadable` findings.
- `ExecutableSurfaceVetter` raises `auto-run-hook` per hook, scans command text
  with `RuntimeFetch`, follows launched files to depth 3, and reports a binary or
  oversized launched file as `hook-target-unscanned`.

What Claude Code does with a mod (plugin-authoring reference, build 2.1.287):
`hooks/hooks.json` names modules under `modules`, paths relative to that file.
The module and every plugin file it imports are `.ts`, `.tsx`, `.jsx`, `.js`,
`.mjs`, `.cjs`, `.mts` or `.cts`, ES modules, imported with an `import`
declaration; a module holding `import()` does not load. `claude-code` is a
types-only import. Hooks are added with `on(event, matcher?, hook)`; the engine
interface is the hook's first parameter, conventionally `$`.

## Goals / Non-Goals

**Goals:**

- A mod is never shown or vetted as a plugin without executable surface.
- Every file a module loads is either scanned or reported as unscanned.
- A hooks object the reader does not understand is a finding, not an empty list.

**Non-Goals:**

- **A TypeScript parser.** The scan is lexical (D2); its limits are documented,
  as the command-hook scan's are.
- **Running or type-checking the module** (`claude plugin validate`): that
  needs Claude Code in the gateway, and would execute untrusted code.
- **Separate findings for every `$` noun.** `$.fs`, `$.agent`, `$.tool`,
  `$.clock`, `$.ui`, `$.state` and the rest are listed in the inventory and in
  the `auto-run-module` message, not flagged one by one.

## Decisions

### D1. `modules` is read wherever a hooks object is read

`Reader.hooks()` handles `modules` beside `hooks`, so `hooks/hooks.json`, a
`plugin.json` `hooks` path or inline object, and a marketplace entry's all
yield modules. The reference documents only `hooks/hooks.json`; reading the
other places too costs nothing and fails in the safe direction (a module shown
that Claude Code would not load, never the reverse). Module paths resolve
against the declaring file's directory; for an inline declaration, against the
plugin root. A path that escapes the plugin root is a problem, not skipped.

`Components` gains `hookModules: List<HookModule>`:

```
HookModule(path, location, events: List<Site>, uses: List<Site>,
           files: List<String>, unscanned: List<Problem>)
Site(name, location)        // location = path:line
```

`files` is the module plus every import followed; `unscanned` lists what could
not be read (D3). `PluginContent` exposes `hookModules` (OpenAPI
`PluginHookModule`, `PluginSite`). `hookModules` is a separate list rather than
`Hook` rows with `type: "module"` because a module has many events and no single
`runs`; overloading `Hook` would make every existing consumer misread it.

*Alternative:* one `Hook` per `on(...)` event. Rejected: the unit a reviewer
approves and a waiver scopes is the module file, and an event registered in a
helper file would be detached from the module that loads it.

### D2. A lexical scan in a new `HookModules` class in `ingestion`

Over the module text, comments stripped (`//…`, `/*…*/`, string contents kept):

- events: `\bon\(\s*(['"`])([\w.:-]+)\1`, at its line;
- uses: `\$\.(\w+)`, distinct names at their first line;
- imports: `import … from '…'`, `import '…'`, `export … from '…'`. A relative
  specifier resolves against the importing file with Claude Code's suffixes
  (the exact path, then each suffix, then `/index` + each suffix). `claude-code`
  and `claude-code/*` are ignored (types only). Any other bare specifier is an
  unscanned problem: the vetter cannot see a package outside the snapshot.

*Revised during apply (adversarial pass):* a regex literal holding `//`
(`/a\/\//`) was blanked as a comment, hiding an import or a `$.process` after it
on the same line. The blanker now keeps a regex literal where one can start (after
an operator, an opening bracket or nothing), and imports are matched on the raw
text as well as the blanked one, so a hidden or commented-out import is followed
(a commented import of a missing file is then reported unscanned, which errs
toward the reviewer seeing it).

Imports are followed breadth-first with a visited set and capped at 50 files
(the cap itself is reported as an unscanned problem when hit). Events and uses
are collected across all followed files, located in the file they appear in.

Kept in `ingestion` beside `PluginComponents` because the inventory needs the
events and uses; no new package. Binary detection uses a strict UTF-8 decode, as
`ContentRules.text` does.

*Alternative:* a JavaScript parser (e.g. GraalJS or a TS grammar). Rejected for
a first cut: a new dependency for a scan whose answers a reviewer reads, not
one that decides on its own; the module still blocks review as a whole via its
`auto-run-module` finding. Indirection (`const p = $.process`, a renamed `$`)
walks past it, and is documented.

### D3. Findings

| Rule | Severity | Location | When |
| --- | --- | --- | --- |
| `auto-run-module` | medium | `hooks.json:line` naming the module | every module; message lists events and uses |
| `module-steers-agent` | medium | the `on('tool.call' \| 'prompt.compose')` line | the module hooks either |
| `module-runs-process` | medium | first `$.process` line | reached |
| `module-calls-model` | low | first `$.model` line | reached |
| `RuntimeFetch` rules | high | `file:line` | in the module or an import, as a launched script |
| `hook-target-unscanned` | medium | the file, or the import line | missing, binary, oversized, bare package, cap hit |
| `hook-config-unreadable` | medium | the hooks file | unknown shape (D4), or a module path escaping the root |

Separate rule ids per capability so a waiver scoped to one does not cover a
capability a later version adds. `module-calls-model` is low: a model call
spends and can carry context, but changes nothing on the machine. Module files
join the hooks' `scanned` set so `installed()` never scans them twice.

### D4. Unknown shapes

In a hooks object: `hooks` (object), `modules` (array of strings) and
`description` (string) are recognised. With neither `hooks` nor `modules`
present the object is a bare event map, and each key's value must be a list of matcher groups (an array of objects).
Any other key, or a recognised key of the wrong type, adds a problem
`"<key>" is not a hook declaration this reader recognises, so hooks it holds
were not read`. Inside an event map, a value that is not a list of matcher groups is the same
problem. This is shape, not a list of event names, so a new event Claude Code
adds still reads.

## Risks / Trade-offs

- [False negatives from indirection] → the module is always `auto-run-module`;
  docs say the capability rules match shapes.
- [False positives from `$.process` in a comment or string] → comments are
  stripped; a string mention still fires, which errs toward the reviewer seeing it.
- [D4 flags a harmless extra key some marketplace uses] → medium, waivable per
  group; the message names the key.
- [API change] → additive field; `openapi.json` diff checked additive.

## Migration Plan

None: additive API field, no schema change. A held or approved snapshot gains
findings at its next vet/re-vet, which is the intended effect.

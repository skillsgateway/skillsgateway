# Proposal: the portal shows invisible and bidirectional control characters

## Why

A reviewer approves what the portal shows them. Text that comes from an upstream
repository — plugin and skill names, descriptions, file paths, finding locations
and the lines of a file — can contain Unicode format characters: bidirectional
controls that reorder the characters around them, and zero-width characters that
render as nothing. The browser applies them, so the reviewer reads a rendering of
the text rather than the text. The prompt-injection vetter already reports such
characters in instruction files (`invisible-characters`), but the name or line
the reviewer reads next to that finding still shows them applied.

## What Changes

- New `GW_APPROVAL_0037 — The portal shows invisible and bidirectional control
  characters in upstream text as visible tokens`: every Unicode format character
  (general category Cf, which includes U+200B–U+200F, U+202A–U+202E, U+2060–U+2064,
  U+2066–U+2069, U+061C, U+FEFF and the tag block) in upstream-controlled text is
  rendered as a visible, named token such as `⟨U+202E⟩`, never applied and never
  stripped. A zero-width joiner between two pictographs (an emoji sequence) is left
  as it is, as the prompt-injection vetter leaves it. Searching, selecting and
  addressing still use the text as written.
- New `GW_APPROVAL_0038 — A source line that contains invisible or bidirectional
  control characters is marked in words`: in the file view, such a line carries a
  written marker, whether or not a vetter finding is on it.
- New `SVC_GW_APPROVAL_0040` … `SVC_GW_APPROVAL_0043` verify the two.
- Not **BREAKING**: portal-only, no API change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-preview`: adds `GW_APPROVAL_0037` and `GW_APPROVAL_0038`.

## Impact

- Portal: a shared helper (`lib/visible-controls.ts`) and a `VisibleText`
  component, used by the source view, the file tree, the file changes, the
  snapshot inventory, the content diff, finding locations, the name-collision
  notice, the Markdown and JSON views, the adoption report and the marketplace
  description.
- Docs: `reference/portal.md`; one sentence in `concepts/vetting.md`.

The capability map absorbs this change: it adds no backend package, object type,
role, sweep or configuration leaf.

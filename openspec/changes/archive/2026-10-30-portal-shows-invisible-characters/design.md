# Design: portal-shows-invisible-characters

## Context

React escapes every text node, so upstream text never becomes markup; but the
browser still applies the Unicode format characters inside it. The prompt-injection
vetter's `invisible()` set (zero-width characters and joiners, bidirectional
embeddings, overrides and isolates, U+2060–U+2064, the byte-order mark, the tag
block) is a subset of general category Cf, and its `emojiJoiner()` exempts a
zero-width joiner between two pictographs (GW_VETTING_0069 — Prompt-injection
markers quoted or prohibited in their own clause warn instead of blocking, and
ordinary vocabulary raises none).

Two open PRs edit `source-view.tsx` (a `firstLine` prop, and the findings-by-line
loop). This change touches only the line text, the line's description list and a
marker beneath the line, and leaves the component's JSDoc alone, so either rebase
stays mechanical.

## Goals / Non-Goals

- Goal: what the reviewer reads is the text's code points, in order, with nothing
  applied invisibly.
- Non-goal: changing the vetter, the API or any stored text. Display only.
- Non-goal: confusable (homoglyph) letters. Those are visible characters; the
  name-collision check (GW_APPROVAL_0019) is where they are handled.
- Non-goal: the finding *message* text in the vetting report and vetting flow.
  An open PR restructures exactly those lines; they follow once it lands. The
  message in the source view and the file's findings list is covered here.

## Decisions

**D1. The set is `\p{Cf}`, minus an emoji joiner.** One Unicode property covers
every class the vetter flags plus the rest of Cf (U+061C, U+00AD, U+FFF9–FFFB,
…), so a newly assigned format character is covered without a list to maintain.
A U+200D is left as it is only when the vetter would leave it: the code point
before it (skipping one U+FE0F or skin-tone modifier) and the one after it are
pictographic by the vetter's own ranges. Alternative: mirror the vetter's explicit
list. Rejected: it misses U+061C, which the issue names, and other Cf characters
that are equally invisible.

**D2. A token, never a removal.** Each character becomes `⟨U+XXXX⟩` in a bordered
inline marker, with a screen-reader-only name ("right-to-left override") and a
title. Stripping would show a clean name that is not the name served; the token
shows exactly where the character sits. The bracketed form is the same in
attribute strings (`title`, `aria-label`), so an accessible name still contains
its visible text.

**D3. One regex pass.** `revealControls(text)` runs one global `matchAll` and
returns the segments plus whether any control was found; text without one is
returned as itself, so the 128 KiB source view costs one scan per line and no
extra allocation on clean lines. The source view uses that one result for both
the rendering and its line marker.

**D4. Display only.** Callbacks, keys, search queries, addresses and the copied
link all keep the original string; only the rendered child changes.

**D5. The line marker is words.** A line with a token gets a note beneath it —
"Contains invisible or bidirectional control characters, shown as ⟨U+…⟩" — and
the line is described by it, like a finding note. It is not a `role="note"`,
which the source view keeps for findings.

## Risks / Trade-offs

- [A selection copied from the page copies the token, not the character] → That
  is the safe direction; the raw file stays one click away through the explorer's
  copy-link and the git facade.
- [A legitimate soft hyphen or right-to-left mark in a description now shows as a
  token] → Accepted: the reviewer is reading for exactly what is there.

## Migration Plan

None: portal-only, no state.

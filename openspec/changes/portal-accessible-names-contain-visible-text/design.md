## Context

Four components give a button an `aria-label` written by hand next to the markup it
describes. The label and the rendered text are two strings that nothing ties together,
so they have drifted:

| Control | Visible text | `aria-label` today |
|---|---|---|
| Directory row | `plugins` `2 changed` (or a bare file count `3`) | `plugins, 3 files, 2 changed` |
| File row | the name or path, `high · 2`, `4 KiB` or `removed` | `<path>, <size or status>, 1 finding, highest high` |
| Vetting flow stage | eyebrow, label, state, meta line, chips | `<chain label>: <label>, <state>` |
| Re-run button | `Re-run the chain now` / `Re-running the chain…` | `Re-run the vetting chain on snapshot N` |
| Clear button | `Clear override` / `Clearing…` | `Clear every chain override on <marketplace>` |

axe-core 4.14 reports each of these as `label-content-name-mismatch`. The Storybook
run on #646's lockfile shows 27 failing stories in five files, all this one rule and
all on the controls above.

## Goals / Non-Goals

**Goals:**

- Every control above has an accessible name that contains its visible text, in the
  order it is rendered, in every state the control has (idle, pending, selected).
- The name and the visible text come from the same values, so they cannot drift again.
- The Storybook story tests pass with axe-core 4.14.

**Non-Goals:**

- No visible change. The bare directory count `3` stays a bare count; whether it should
  read `3 files` is a design question for the `impeccable` audit, not this change.
- No new requirement and no new component library or helper beyond one small function.
- No sweep of the other ~70 `aria-label` uses. axe reports only these, and the rule is
  now a standing gate: a control that drifts later fails its story.

## Decisions

### Decision 1: the name is the visible text, then the context in parentheses

For the two buttons whose label is an action plus a subject, the name becomes
`<visible text> (<context>)`: `Re-run the chain now (snapshot 1)`,
`Clear override (corp-marketplace)`. The visible string is the one the button renders,
including its pending form (`Re-running the chain…`), so the rule holds in both states.

Alternatives: keep the long label and make the visible text match it (changes the UI
and widens the button); drop the `aria-label` and lose the subject, which matters
because `Clear override` repeats in every table row. Parentheses rather than a
suffix phrase because the context is a bare noun, and that reads the same for every
state without needing a preposition.

The shared `Clear every chain override on <scope>` string in
`lib/vetting-overrides.ts` is also used as a confirmation message; only the button name
changes, not the message.

### Decision 2: tree rows build their label from the markers they render

`DirectoryMarker` and `FileMarker` already compute the text they show. Each becomes a
small function returning that text, used both to render the marker and to build the
row's label: `<shown name> <marker text>` first, then the spelled-out meaning in
parentheses (`plugins 2 changed (3 files)`, `reference.md high · 1 4 KiB (plugins/hello/…/reference.md, 1 finding, highest high)`).
The expansion stays, because `2 changed` and `high · 1` are not self-explanatory to a
screen-reader user, but it follows the visible text instead of replacing it.

**Found while implementing: axe joins sibling elements' text with no separator.** A row
whose visible text is `<span>LICENSE</span><span>1 KiB</span>` is `LICENSE1 KiB` to axe, so
no natural label (`LICENSE 1 KiB`) contains it; only an absurd one would. Checked in
headless Chromium against axe-core 4.14: with a real space text node between the spans
(`<span>…</span>{" "}<span>…</span>`) the spaced label passes, and a wrong label still
fails. A whitespace-only text node in a flex container takes no space in the layout, so
nothing visible moves. The rows carry `{" "}` between their spans for that reason.

Alternative: drop the labels and let the content name the rows. Rejected: the markers
are terse by design (a reviewer scanning a tree), and spoken as `plugins 3` they lose
their meaning.

### Decision 3: flow stages drop their `aria-label`

A stage button's content is already a complete description: eyebrow, label, state,
meta and chips. Its name becomes that content. The `<chain label>:` prefix is not lost:
the enclosing `<ol>` already carries `aria-label={label}` and is the list the buttons
belong to.

Alternative: keep a composed label containing every field. Rejected: it is the same
duplication that drifted, with five inputs instead of two.

The same space text nodes go between the pieces of the button, because a button named by
its content also needs them: without them the name ran together (`Step 1secret-scanfail`).

### Decision 4: tests assert the property, not just the new strings

Updating the queried names fixes the tests but would let the next hand-written label
drift again unnoticed on a lock file that pins an older axe. Each affected story or
component test also asserts, with `labelContainsText` (`lib/accessible-name.ts`), that the
control's `aria-label` contains its visible text, which holds independently of the axe
version. The helper ignores whitespace and case, as axe does.

## Risks / Trade-offs

- **Screen-reader names get longer** (flow stages most of all, now eyebrow + label +
  state + meta + chips) → this is what a sighted user is shown; the list's own name
  keeps its context. The `impeccable` audit is the place to object.
- **Anything that finds these controls by name breaks** → the in-repo consumers are the
  component tests, stories and `e2e/portal.spec.ts`, all listed in the tasks. There is
  no documented or API-visible use.
- **This PR cannot show the failure on its own CI**, because the lock file here still
  pins axe-core 4.13 and bumping it is Renovate's (#646) → the failing stories are
  reproduced locally with #646's lock file, and #646's CI is the proof after merge.
- **Other controls may drift on a future axe release** → they fail their story the same
  way and get the same fix; the property tests of Decision 4 cover the four touched here.

## Open Questions

- Should the directory's bare file count read `3 files`? It would make the label
  identical to the visible text. Left for the `impeccable` audit.

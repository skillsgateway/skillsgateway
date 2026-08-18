# Evidence: setup-wizard-preview-pane

One final fresh run of every gate after the last code edit. Commit SHA at the
bottom.

## Discipline notes

- Non-vacuity mutant (killed, then restored — verified by re-running the
  test): the `requireApproverOfSnapshot` line removed from the files read →
  `SVC_GW_0080`'s enforcement method failed exactly on the no-role denial
  (`Status expected:<403> but was:<200>`); with the line restored the method
  passes. The authorization gate is proven load-bearing, not decorative.
- Negative cases are first-class: traversal-shaped paths (`../../../etc/passwd`,
  absolute, `plugins/../..`) answer 404 because `TreeWalk.forPath` matches tree
  entries and nothing else — there is no filesystem path to escape; the
  oversized fixture (≈248 KiB) comes back cut at exactly 128 KiB with
  `truncated: true` and its full size; the NUL-carrying blob comes back
  `binary: true` with no text field at all.
- The quarantine boundary: the three reads live under `/api/**` (OIDC chain);
  the only thing that crosses between the published and quarantine opens is
  the 40-character baseline SHA. Nothing in the change touches `/git/**`,
  `GitFacadeConfiguration`, or `ApprovalService`.
- The preview e2e drives a real delta: approve through the portal, advance the
  upstream fixture with a host-config-isolated `git` commit, re-ingest, and
  read the held snapshot's tree, the inertly rendered SKILL.md (the planted
  `<img onerror>` line is asserted visible as text), and the diff naming the
  modified and added paths against the served baseline. The wizard e2e mints a
  real token and proves the placeholder is back after close/reopen with the
  old cleartext nowhere on the page.
- Markdown inertness is tested at three levels: component test
  (`embedded_html_is_shown_as_text_and_never_becomes_markup`, plus a
  `javascript:` link rendered non-navigable), a Storybook story with axe, and
  the e2e assertion above. There is no `dangerouslySetInnerHTML` anywhere in
  the change.
- No existing SVC test was weakened; RoleEnforcementTests gained one method
  and lost nothing. Impeccable: the mechanical detector
  (`detect.mjs --json` over the four changed/new UI files) reported zero
  findings; the audit/harden review pass fixed one a11y defect during
  development (a duplicate accessible name between the Files tab and the tree
  landmark, relabeled `File tree of snapshot {id}`). No findings dismissed.
- One traceability correction landed as its own commit: the TS tag scanner
  splits `@Requirements` on commas and binds to the documented declaration —
  the ids are now comma-separated on the exported components, and GW_0079's
  category uses the schema's `interaction-capability`.

## Gates

### `./mvnw clean verify`

```
[INFO] BUILD SUCCESS
[INFO] Total time:  45.606 s
surefire aggregate: tests=81 failures=0 errors=0 skipped=0
```

### `(cd src/main/frontend && pnpm e2e)`

```
  10 passed (22.0s)
```

### `reqstool status local -p docs/reqstool`

```
76/76 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 19 passed, 0 failed (19 items)
```

### `mkdocs build --strict`

```
INFO    -  Documentation built in 0.44 seconds
```

## Commit

`f54b808` (implementation; the archive commit follows it and changes no code)

# Evidence: portal-user-menu

Commit: `bdf0523` (`fix(portal): comma-separate multiple ids in @Requirements JSDoc tags`,
on top of `71995d3`, the implementation commit).

All commands below are one final fresh run, after the last code edit, on a
worktree fast-forward-merged with `origin/main` at `9623730` (`feat(tokens):
record and show when a token was last used (#402)`).

## `./mvnw clean verify`

```
$ MAVEN_OPTS="-Xmx2g" ./mvnw clean verify
...
[INFO]  Test Files  13 passed (13)
[INFO]       Tests  92 passed (92)
...
[INFO] Spotless.Java is keeping 353 files clean - 0 needs changes to be clean, 353 were already clean, 0 were skipped because caching determined they were already clean
[INFO] --- checkstyle:3.6.0:check (default) @ skills-gateway-server ---
[INFO] You have 0 Checkstyle violations.
...
[INFO] BUILD SUCCESS
[INFO] Total time:  06:50 min
```

## `(cd src/main/frontend && pnpm test:stories)`

```
$ pnpm test:stories
 ✓ |storybook (chromium)| src/components/markdown-view.stories.tsx (2 tests) 893ms
 ✓ |storybook (chromium)| src/components/setup-wizard.stories.tsx (2 tests) 776ms
 ✓ |storybook (chromium)| src/components/app-layout.stories.tsx (5 tests) 1100ms
   ✓ Roles From Every Source 792ms
 ✓ |storybook (chromium)| src/pages/tokens.stories.tsx (2 tests) 469ms
 ✓ |storybook (chromium)| src/pages/adoption.stories.tsx (3 tests) 488ms
 ✓ |storybook (chromium)| src/components/vetting-flow.stories.tsx (8 tests) 855ms

 Test Files  6 passed (6)
      Tests  22 passed (22)
```

All 5 `Shell/UserMenu` stories (RolesFromEverySource, NoRole, TruncatedClaims,
DevelopmentEscapeHatch, SessionUnreadable) ran with axe violations as errors —
`app-layout.stories.tsx (5 tests)` above is that suite; none failed.

## `(cd src/main/frontend && pnpm e2e)`

```
$ pnpm e2e
Running 18 tests using 1 worker

  ✓   3 [chromium] › the_user_menu_names_the_session_and_each_role_with_its_source (772ms)
  ✓   4 [chromium] › tokens_are_reached_from_the_user_menu_and_the_route_still_resolves (837ms)
  ✓   5 [chromium] › signing_out_ends_the_session (664ms)
  ✓   6 [chromium] › token_cleartext_is_shown_once_and_revocation_marks_it_revoked (1.1s)
  ...
  18 passed (1.1m)
```

## `reqstool status local -p docs/reqstool`

```
$ reqstool status local -p docs/reqstool
  GW_AUTH_0044        skills-gateway
  GW_AUTH_0045        skills-gateway
  GW_AUTH_0046        skills-gateway
  ...
INCOMPLETE (0)
230/230 complete · 0 incomplete · PASS
```

(An intermediate run flagged `GW_AUTH_0044`/`GW_AUTH_0046` as "not implemented"
because `app-layout.tsx`/`app-layout.stories.tsx` originally space-separated
three ids in one `@Requirements` tag rather than comma-separating them per the
codebase's own convention; fixed in `bdf0523`, re-run above is clean.)

## `openspec validate --all --strict`

```
$ openspec validate --all --strict
✓ change/portal-user-menu
...
Totals: 32 passed, 0 failed (32 items)
```

## `mkdocs build --strict`

```
$ mkdocs build --strict
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 1.30 seconds
```

## Design harness

- `node .claude/skills/impeccable/scripts/detect.mjs --json src/main/frontend/src/components/app-layout.tsx src/main/frontend/src/components/ui/dropdown-menu.tsx` → `[]` (no findings).
- `/impeccable audit` (manual, code-level): no P0/P1/P2 findings. One
  deliberate, already-documented deviation from generic web guidance: 32px
  control height is below the 44px touch-target guideline — this product is a
  desk tool, not mobile-first, and every control in the shell shares that
  height (`DESIGN.md`, "Mobile" / density language).
- `/impeccable harden` (manual): verified against a long username
  (`truncate`/`break-all`), many roles (menu content scrolls), an unmapped
  role source (renders verbatim), a failed `/api/me` read, and a failed sign
  out (control re-enables, toast shown). No changes needed.
- Keyboard: Tab/Enter/Space opens the trigger, arrows traverse items, Escape
  closes and restores focus to the trigger — asserted in
  `the_user_menu_names_the_session_and_each_role_with_its_source` (Playwright).
- Both themes: `motion-reduce:animate-none motion-reduce:duration-0` on the
  popup; before/after screenshots (light and dark) captured to
  `~/agents/skillsgateway/pr-395-screenshots/` (not uploaded).

## 1. Requirements (SSOT first)

- [x] 1.1 Add `GW_AUTH_0044`, `GW_AUTH_0045` and `GW_AUTH_0046` to `docs/reqstool/requirements.yml`
- [x] 1.2 Add `SVC_GW_AUTH_0044`, `SVC_GW_AUTH_0045` and `SVC_GW_AUTH_0046` to `docs/reqstool/software_verification_cases.yml`

## 2. The menu primitive

- [x] 2.1 Add the Base UI menu component to `src/components/ui/` from the shadcn registry (D1)
- [x] 2.2 Confirm it carries `aria-haspopup`/`aria-expanded`, escape-to-close and arrow traversal without extra wiring

## 3. Shell

- [x] 3.1 Build the user menu in `components/app-layout.tsx`: trigger naming the signed-in user, identity, roles with their source, theme cycle, "Your tokens", sign out (`GW_AUTH_0044`)
- [x] 3.2 Render role sources as sentences, marketplace-scoped approver roles named, unknown sources verbatim (D3)
- [x] 3.3 State the no-role and `claimsTruncated` cases in words rather than as an empty list (D4)
- [x] 3.4 Sign out: POST `/logout` with the CSRF header, then navigate to `/`; under the development escape hatch replace the control with an explanation (`GW_AUTH_0045`, D5)
- [x] 3.5 Move the theme cycle into the menu unchanged — same order, default, storage key and accessible name (D6)
- [x] 3.6 Remove the sidebar "Access" group and the sidebar identity footer; keep the `/tokens` route and the Overview card's action (`GW_AUTH_0046`, D7)
- [x] 3.7 Add the `@Requirements` JSDoc tags on the shell components

## 4. Verification

- [x] 4.1 Storybook stories for the personas: several roles from several sources, no role at all, truncated claims, the development hatch, and a failed `/api/me` read — axe as error, both themes (`SVC_GW_AUTH_0044`, D8)
- [x] 4.2 Story covering the tokens page's empty and error states for the no-role persona (`SVC_GW_AUTH_0046`) — implemented as component tests (`tokens.test.tsx`) against the same msw-mocked responses a story would use, rather than as Storybook stories: `IssuedTokenDialog` is the page's only story-worthy stateful piece (`tokens.stories.tsx`), and the empty/error list states are otherwise page-level data states already covered by the axe-checked shell stories' pattern elsewhere in this file.
- [x] 4.3 Component tests: the theme cycle still cycles and persists from inside the menu; sign out posts to `/logout`; the hatch shows no sign-out control (`SVC_GW_AUTH_0045`)
- [x] 4.4 Playwright: open the menu from the header, reach the tokens page through "Your tokens", and confirm `/tokens` still resolves when navigated to directly; `@SVCs` tags with snake_case titles
- [x] 4.5 Update the existing e2e specs that click the sidebar "Access tokens" link

## 5. Design harness

- [x] 5.1 Run `/impeccable audit` on the shell; fix what it flags or record the dismissal with a reason — clean (no P0/P1/P2 findings; detector run: `node .claude/skills/impeccable/scripts/detect.mjs --json` on `app-layout.tsx` and `ui/dropdown-menu.tsx` returned `[]`). One deliberate deviation from generic web guidance, already recorded in `DESIGN.md`: the 32px control height is below the 44px touch-target guideline — this product is declared a desk tool, not mobile-first, and every other control in the shell shares the same height.
- [x] 5.2 Run `/impeccable harden` on the shell — verified against long usernames (`truncate`/`break-all`), many roles (menu content scrolls via `max-h-(--available-height) overflow-y-auto`), an unmapped role source (renders verbatim), a failed `/api/me` read (error state), and a failed sign-out (control re-enables with a toast). No changes needed.
- [x] 5.3 Verify keyboard-only operation, reduced motion, and both themes; capture before/after screenshots — Tab/Enter/Space opens the trigger, arrow keys traverse items, Escape closes and restores focus to the trigger (Playwright asserts the Escape case); `motion-reduce:animate-none motion-reduce:duration-0` on the popup. Screenshots: `~/agents/skillsgateway/pr-395-screenshots/{before,after}-shell-{light,dark}.png`.
- [x] 5.4 Update `DESIGN.md` (Layout, Navigation, Theme control) to describe the shell as shipped

## 6. Documentation

- [x] 6.1 `docs/manual/reference/portal.md`: navigation table, the user menu, where tokens live, the theme control, and retire the "there is no logout control" note
- [x] 6.2 Any guide that sends the reader to "Access tokens in the sidebar" — `docs/manual/guides/consuming-skills.md` and `docs/manual/reference/api/index.md` ("the sidebar footer")

## 7. Gates and archive

- [x] 7.1 `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`, `reqstool status local -p docs/reqstool`, `openspec validate --all --strict`, `mkdocs build --strict`
- [x] 7.2 Write `evidence.md` from one final fresh run after the last edit
- [x] 7.3 Archive the change as the final commit

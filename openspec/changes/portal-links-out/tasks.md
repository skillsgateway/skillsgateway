## 1. Portal navigation

- [x] 1.1 Add the documentation-site and source-repository links to the **Tools**
  group in `src/main/frontend/src/components/app-layout.tsx`, beside the API
  reference: `target="_blank"`, `rel="noopener noreferrer"`, an `aria-hidden`
  lucide icon, and an accessible name that says the link opens a new tab.
- [x] 1.2 Keep the two addresses as module constants next to the component, with
  a one-line comment pointing at `mkdocs.yml`'s `site_url` and `repo_url` as the
  source they are kept in step with.

## 2. Harness

- [x] 2.1 Extend `app-layout.test.tsx`: both links are found by role and
  accessible name, and each carries `target="_blank"` and a `rel` containing
  `noopener`.
- [x] 2.2 Check `app-layout.stories.tsx` — it stories `UserMenuView` only, not the
  sidebar, so the two links add no story state; nothing to extend.
- [x] 2.3 `/impeccable audit` on the portal shell: the bundled detector reports no
  findings for `app-layout.tsx`, no new color or token is introduced, and the
  accessible names are asserted by test. Sidebar nav rows are ~32px tall, under
  the 44px touch-target guideline — pre-existing and systemic to every nav row,
  not introduced here; recorded in the PR body rather than changed.

## 3. Documentation

- [x] 3.1 Add both rows to the navigation table in
  `docs/manual/reference/portal.md`, marked as leaving the portal.

## 4. Gates

- [ ] 4.1 `./mvnw clean verify`
- [ ] 4.2 `(cd src/main/frontend && pnpm test:stories)`
- [ ] 4.3 `(cd src/main/frontend && pnpm e2e)`
- [ ] 4.4 `reqstool status local -p docs/reqstool` — must end PASS
- [ ] 4.5 `openspec validate --all --strict`
- [ ] 4.6 `mkdocs build --strict`
- [ ] 4.7 `evidence.md` from one final fresh run after the last edit

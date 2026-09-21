## 1. Requirement

- [x] 1.1 Add GW_AUTH_0047 — The portal states the build it is running to
      `docs/reqstool/requirements.yml`: the system shall report the build version
      of the running gateway on its authenticated session read, taken from the
      build itself and from no configurable source, reporting its absence rather
      than a substitute when the build carries none, and shall state it in the
      portal shell.
- [x] 1.2 Confirm the id is free in `requirements.yml` and in every in-flight
      `openspec/changes/`.
- [x] 1.3 Add SVC_GW_AUTH_0047.

## 2. The version on the session read

- [x] 2.1 `MeView` gains `version`, populated from `BuildProperties`.
- [x] 2.2 `BuildProperties` is optional: absent means null, never a placeholder.
      Test both, because every test context is the absent case.
- [x] 2.3 Regenerate `openapi.json` and `types.gen.ts`; `OpenApiContractTests`
      must pass.

## 3. The shell

- [x] 3.1 Rename the nav group `Tools` → `Reference`. Nothing else in the group
      changes.
- [x] 3.2 The sidebar footer states the version, and renders nothing at all when
      there is none.
- [x] 3.3 Component tests: the group name, the version rendered, and the absent
      case rendering no footer.
- [x] 3.4 **No story, deliberately.** `app-layout.stories.tsx` stories
      `UserMenuView` and not `AppLayout`, because the shell needs a router and a
      query client; extracting the footer into a presentational shell purely to
      story it would test a component that does not ship. Both of its states —
      a version, and none — are covered through the real shell in
      `app-layout.test.tsx`, which is the stronger assertion.

## 4. Tests

- [x] 4.1 A Java test that `me` reports the version this build carries.
- [x] 4.2 Update any existing test or e2e spec that queries the old group name —
      the rename is the point, so they move with it rather than being loosened.

## 5. Documentation (same PR)

- [x] 5.1 `reference/portal.md`: the nav table's group name, and the footer.
- [x] 5.2 The `me` response's new field, wherever that response is documented.

## 6. Gates

- [ ] 6.1 Run all gates fresh after the last edit and write `evidence.md`.
- [ ] 6.2 `/impeccable audit` and `harden` on the shell — a changed portal
      surface, though not a new page, so no `critique`.

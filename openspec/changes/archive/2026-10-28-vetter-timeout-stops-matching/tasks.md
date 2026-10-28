# Tasks: vetter-timeout-stops-matching

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_VETTING_0062 and SVC_GW_VETTING_0064 in `docs/reqstool/`.

## 2. Cancellation reaches the match (SVC_GW_VETTING_0064)

- [x] 2.1 `VetterCancellationTests`: an interrupted `RuntimeFetch.scan` of a
  pathological line, and an interrupted `ExecutableSurfaceVetter` run over a
  hook script holding it, each stop within 2 s. A never-closing TOML section
  header is read within 1 s, and the real section after it is still found.
  Watch all three fail first.
- [x] 2.2 `InterruptibleText` (`@Requirements GW_VETTING_0062`), every content
  match site wrapped, and `SECTION` possessive with the name trimmed. Make 2.1
  green with every vetting suite unchanged.

## 3. Documentation

- [x] 3.1 `concepts/vetting.md` says a timed-out built-in stops working. Verify
  with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.

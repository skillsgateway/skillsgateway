# Tasks: linear-line-numbers

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_VETTING_0063 and SVC_GW_VETTING_0065 in `docs/reqstool/`.

## 2. Linear location (SVC_GW_VETTING_0065)

- [x] 2.1 Four time-bounded tests, each also checking correct first and last
  locations:
  - `HookModulesTests`: a dense-import module, and a module with many events;
  - `LinearLocationTests`: a dense-injection instruction file, and a
    dense-secret script.

  Watch all four time out first.
- [x] 2.2 `LineIndex`, its use in `ContentRules`, `SecretScanVetter` and
  `HookModules`, and the look-back import discovery
  (`@Requirements GW_VETTING_0063`). Make 2.1 green with every vetting and
  ingestion suite unchanged.

## 3. Gates and evidence

- [x] 3.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.

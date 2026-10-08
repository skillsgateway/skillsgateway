# Tasks: external-vetter-timeout

## 1. Requirements (reqstool)

- [ ] 1.1 Revise GW_VETTING_0025 (revision 0.3.0) and add SVC_GW_VETTING_0062
  under it in `docs/reqstool/`; verify with
  `openspec validate external-vetter-timeout --strict`.

## 2. Per-vetter time limit (SVC_GW_VETTING_0062)

- [ ] 2.1 `VettingTimeLimitTests` (container-free, `@SVCs SVC_GW_VETTING_0062`)
  with the chain-wide limit set short: a built-in-style vetter that outruns it
  is an `ERROR` naming the chain-wide limit; an external connector with a longer
  `read-timeout` whose endpoint answers after the chain-wide limit still
  passes; a connector with no `read-timeout` against a hanging endpoint is an
  `ERROR` raised by the HTTP client within the chain-wide limit, not the chain's
  "timed out after". Watch the long-read-timeout case fail first.
- [ ] 2.2 `Vetter.timeLimit`, `runGuarded` using it, `ExternalVettingConnector`
  overriding it (`connect + read + 5s`) and taking the chain-wide limit as its
  read timeout when none is set; `ExternalConnectorProperties` keeps an unset
  `read-timeout` as null; `@Requirements GW_VETTING_0025` on the override.
  Make 2.1 green; update existing constructor call sites without changing any
  assertion.
- [ ] 2.3 Registrar passes `skills-gateway.vetting.timeout` to each connector;
  extend `ExternalConnectorRegistrationTests` (`@SVCs SVC_GW_VETTING_0062`) so a
  connector with no `read-timeout` bound under a non-default chain-wide limit
  reports a time limit derived from it. Verify `ConfigSurfaceBudgetTests` and
  `ContextBudgetTests` are unchanged.

## 3. Documentation

- [ ] 3.1 `reference/configuration.md` (`vetting.timeout` and
  `external[n].read-timeout` rows), `concepts/vetting.md` and
  `guides/adding-an-external-vetter.md`; verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [ ] 4.1 Run every AGENTS.md gate fresh after the last code edit and record
  commands, result tails and the commit SHA in `evidence.md`.

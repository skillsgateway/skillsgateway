# Tasks: prompt-injection-mention-context

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_VETTING_0069, SVC_GW_VETTING_0072 and SVC_GW_VETTING_0073 in `docs/reqstool/`; verify with `reqstool status local -p docs/reqstool` (after the build).

## 2. Precision (SVC_GW_VETTING_0072, SVC_GW_VETTING_0073)

- [x] 2.1 `PromptInjectionPrecisionTests`: the verbatim lines from `anthropics/skills` and `anthropics/claude-plugins-official` named in #768 raise no high finding (`@SVCs SVC_GW_VETTING_0072`). Watch it fail first.
- [x] 2.2 `PromptInjectionPrecisionTests`: every payload in `PAYLOADS`, plus new disclosure, pipe-to-shell, credential and invisible-character payloads, still raises one high finding; a payload preceded by *never* and a quoted example are each reported once at medium (`@SVCs SVC_GW_VETTING_0073`). Watch the medium case fail first.
- [x] 2.3 `ContentRules.Rule` gains `mentionable`; `apply` downgrades a mentionable match in a mention clause to medium with its own message, keeping the higher finding per rule and line (`@Requirements GW_VETTING_0069`). Verify with 2.1–2.2.
- [x] 2.4 `PromptInjectionVetter`: disclosure, concealment form 1, credential-path and emoji-joiner narrowing per design D3–D5; version 3 (`@Requirements GW_VETTING_0069`). Verify with 2.1–2.2 and the existing GW_VETTING_0040 tests.
- [x] 2.5 Re-run `VettingPrecisionMeasurement` on the five clones from #768 and record before/after high counts in `evidence.md`.

## 3. Documentation

- [x] 3.1 `concepts/vetting.md`, prompt-injection section: mentions are reported at medium, with the downgrade's limit stated. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh` over the new conditions, then every AGENTS.md gate fresh, recorded in `evidence.md`.

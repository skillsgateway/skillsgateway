# Tasks: connect-client-credential-step

## 1. Requirements (reqstool)

- [x] 1.1 Revise GW_AUTH_0014 and SVC_GW_AUTH_0014; add GW_AUTH_0062,
  GW_AUTH_0063 and SVC_GW_AUTH_0068 … SVC_GW_AUTH_0070 in `docs/reqstool/`.

## 2. Wizard

- [x] 2.1 Unit tests (SVC_GW_AUTH_0069, SVC_GW_AUTH_0070) for the composed
  commands, the token field and the reach and ledger statements; watch them
  fail first.
- [x] 2.2 `setup-wizard.tsx`: origin-scoped `store` helper line, prompt-read
  approve line, token field, plaintext/keychain note, reach statement, ledger
  copy (`@Requirements GW_AUTH_0062, GW_AUTH_0063`).
- [x] 2.3 Stories: the minted state shows the token field and unchanged
  commands.
- [x] 2.4 e2e: SVC_GW_AUTH_0014 reads the token from its field; new
  SVC_GW_AUTH_0068 runs the wizard's commands in a shell with an empty `HOME`
  and clones the approved marketplace.

## 3. Documentation

- [x] 3.1 `guides/consuming-skills.md` and `reference/portal.md`; correct
  every "every fetch is recorded" claim in the portal and `docs/manual/`.
  Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 Manual run of the exact commands against a running gateway with an
  empty `HOME`, recorded in `evidence.md`.
- [x] 4.2 Every AGENTS.md gate fresh, recorded in `evidence.md`.

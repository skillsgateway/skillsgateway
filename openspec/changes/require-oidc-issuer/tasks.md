# Tasks: require-oidc-issuer

Worked under `.claude/skills/old-coder` at Tier 3 (authentication trust
boundary). Every new test is shown failing before the code it guards exists,
or against a throwaway mutant when it passes on first run.

## 1. Requirements (reqstool)

- [ ] 1.1 Amend GW_AUTH_0017 — Enterprise identity-provider session integrity —
  from "warn at startup" to "refuse to start" and bump its revision; amend
  SVC_GW_AUTH_0017 to drop the logging clause and bump its revision; add
  SVC_GW_AUTH_0017.2 for the startup cases. Verify with
  `openspec validate require-oidc-issuer --strict`.

## 2. The refusal (SVC_GW_AUTH_0017.2)

- [ ] 2.1 `OidcIssuerRequiredTests` (S1–S8 in design.md) against a stub
  `IdTokenDecoderConfiguration` that still only warns; watch S1–S3, S7 fail.
- [ ] 2.2 Move the decoder factory into `IdTokenDecoderConfiguration`
  (`@Requirements GW_AUTH_0017`), add the refusal; make 2.1 green.
- [ ] 2.3 Pin a placeholder issuer in the test contexts that configure a real
  client id (shared context, forwarded headers, session cookie); verify
  `ContextBudgetTests` passes unchanged.
- [ ] 2.4 Manual mutation: remove the throw, invert the blank test, drop the
  escape-hatch skip, ignore the registrations; each must be killed.

## 3. Harnesses and packaging

- [ ] 3.1 Pin `SKILLSGATEWAY_OIDC_ISSUER=http://localhost:9090/default` in
  `run-e2e.sh` and the container smoke test; verify with `pnpm e2e` (a real
  login succeeds with the issuer compared).
- [ ] 3.2 Chart: `oidc.issuer` `required`, values comment "Required"; update
  `PackagingTests`; verify `helm lint` and `helm template` fail without the
  issuer and render with it.

## 4. Docs

- [ ] 4.1 configuration reference, trust boundaries §6, Kubernetes and Compose
  deployment guides, local development, identity providers; verify
  `mkdocs build --strict`.

## 5. Gates and evidence

- [ ] 5.1 Fresh run of all gates after the last code edit; write
  `evidence.md` with command tails and commit SHA.

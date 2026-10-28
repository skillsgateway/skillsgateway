# Tasks: redact-held-hook-text

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_VETTING_0065, SVC_GW_VETTING_0067 and SVC_GW_VETTING_0068 in `docs/reqstool/`.

## 2. Mask held hook text (SVC_GW_VETTING_0067, SVC_GW_VETTING_0068)

- [x] 2.1 `HeldHookTextRedactionTests`: a session with no role reads `/content`
  and `/vetting` for a held snapshot whose hook command carries a GitHub token.
  The token never appears, and the rest of the command does. Watch it fail first.
- [x] 2.2 `SecretRedactionTests`: each shape is masked with its surroundings
  kept, and text holding no credential comes back unchanged.
- [x] 2.3 `SecretScanVetter.redact`, applied in `describe(Hook)` before
  truncation and to each hook `SnapshotContentService` returns
  (`@Requirements GW_VETTING_0065`).

## 3. Documentation

- [x] 3.1 `concepts/vetting.md` (secret-scan and `auto-run-hook`) and
  `reference/api/marketplaces.md` (`/content` hooks) say credential-shaped text
  reads `[redacted]`. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [ ] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.

## 1. Domain mapping

- [x] 1.1 Assign each of the 172 top-level requirement ids to one of 12
      domains, cross-checked against `openspec/specs/*/spec.md` capability
      membership, not title alone
- [x] 1.2 Validate the mapping: every id in `requirements.yml` covered exactly
      once, no id mapped twice, new ids well-formed (`GW_<DOMAIN>_NNNN`)

## 2. Rename the SSOT and every reference

- [x] 2.1 Rename all 172 top-level ids and their `SVC_` counterparts in
      `docs/reqstool/requirements.yml` and
      `docs/reqstool/software_verification_cases.yml`; the 8 dot-notation
      children (`GW_0158.1`-`.8`) move with their parent automatically via
      substring replacement
- [x] 2.2 Rename every `@Requirements`/`@SVCs` annotation in
      `src/main/java`, `src/test/java` and every JSDoc requirement tag in
      `src/main/frontend`
- [x] 2.3 Rename every id mention in `docs/manual/**` and every ADR in
      `docs/decisions/`
- [x] 2.4 Rename every id mention in `openspec/specs/**` and
      `openspec/changes/**`, including `openspec/changes/archive/**`
      (archived history rewritten deliberately, per this project's choice
      to keep no id in two forms anywhere)
- [x] 2.5 Confirm no real id was left in its old flat form
      (`git grep -oE "GW_[0-9]{4}\b"` after the rename matches only ids that
      were never entered in `requirements.yml` — reserved-then-abandoned
      gaps and provisional ids in unimplemented proposals)

## 3. Id-minting config and docs

- [x] 3.1 `.reqstool-ai.yaml`: blank `req_prefix`/`svc_prefix`, document the
      12 domains and point at ADR 0018
- [x] 3.2 `CONTRIBUTING.md` workflow step 2: describe the domain-prefix
      minting convention
- [x] 3.3 Write `docs/decisions/0018-domain-prefixed-requirement-ids.md` and
      index it in `docs/manual/reference/decisions.md`

## 4. Spec deltas and gates

- [x] 4.1 Delta specs under `specs/<capability>/` for all 27 touched
      capabilities, `## RENAMED Requirements` only (172 renames total, no
      requirement text or scenario changes)
- [x] 4.2 Run every gate one final time and record `evidence.md`
- [x] 4.3 Archive the change as the final commit of the PR

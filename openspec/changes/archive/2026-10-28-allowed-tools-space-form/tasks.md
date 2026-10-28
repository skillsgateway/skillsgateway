# Tasks: allowed-tools-space-form

## 1. Requirements (reqstool)

- [x] 1.1 Add SVC_GW_APPROVAL_0031 under GW_APPROVAL_0007 in `docs/reqstool/`.

## 2. Space-separated tools (SVC_GW_APPROVAL_0031)

- [x] 2.1 `PolicyEnforcementTests`: a skill declaring `allowed-tools: Read Bash(curl:*)`
  is refused by the documented `t.startsWith("Bash")` rule.
  `SkillFrontmatterTests`: the space form, a mixed form and a list entry split
  per tool, and spaces inside parentheses are kept. Watch both fail first.
- [x] 2.2 `SkillFrontmatter` splits on top-level whitespace as well as commas,
  for strings and list entries (`@Requirements GW_APPROVAL_0007`). Make 2.1
  green with the existing tests unchanged.

## 3. Documentation

- [x] 3.1 `guides/policy-rules.md` names both string forms. Verify with
  `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.

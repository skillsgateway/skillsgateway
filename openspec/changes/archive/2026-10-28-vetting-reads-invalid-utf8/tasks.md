# Tasks: vetting-reads-invalid-utf8

## 1. Requirements (reqstool)

- [x] 1.1 Add SVC_GW_VETTING_0063 under GW_VETTING_0043 in `docs/reqstool/`.

## 2. Binary means NUL (SVC_GW_VETTING_0063)

- [x] 2.1 `InvalidUtf8IsTextTests`. Five fixtures that FAIL must still FAIL,
  with the same blocking finding ids, once one 0xFF byte is added on a comment
  line: a skill injection, a token, a hook script that fetches and runs, a hook
  module import that does, and an MCP server script that does. Watch all five
  fail first.
- [x] 2.2 `ContentRules.text` and `HookModules.text` treat only NUL-holding
  content as binary, and decode the rest with replacement characters
  (`@Requirements GW_VETTING_0043`). Two existing tests whose "binary" fixture
  held no NUL get a NUL byte, keeping their assertions about unreadable files.
  The messages say "binary".

## 3. Documentation

- [x] 3.1 `concepts/vetting.md` and `guides/adding-an-external-vetter.md`
  describe binary as holding a NUL byte. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.

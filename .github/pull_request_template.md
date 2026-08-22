<!--
  Overrides the generic organization default in skillsgateway/.github, which has
  no Evidence section. CLAUDE.md requires evidence -- the commands and pasted
  result tails of one final fresh run of all gates after the last code edit.

  The PR title becomes the commit subject on main (squash merge, PR_TITLE), so
  it must be a valid Conventional Commit -- not just the commits inside it.
-->

Closes #

## What & why

<!-- What changes, and the problem it solves. -->

## Notes for review

<!-- Anything a reviewer would otherwise have to reconstruct: renumbered
     requirements, regenerated artifacts, deliberate omissions. Delete if empty. -->

## Evidence

<!--
  Evidence, not adjectives: one final fresh run of every gate AFTER the last
  code edit, with the commit SHA. For an OpenSpec change the full report lives
  in openspec/changes/<name>/evidence.md and archives with it; summarize here.

      ./mvnw clean verify                     # Java + UI gates + packaged jar
      (cd src/main/frontend && pnpm e2e)      # real-browser e2e vs mock OIDC IdP
      reqstool status local -p docs/reqstool  # must end PASS
      openspec validate --all --strict
      mkdocs build --strict

  Use `clean` for the reqstool gate: incremental compilation truncates the
  generated annotation files. State any gate you did not run, and why.
-->

Commit:

## Checklist

- [ ] PR title is a valid [Conventional Commit](https://www.conventionalcommits.org/)
- [ ] Every commit is signed off (`git commit -s`) per the [DCO](https://github.com/skillsgateway/.github/blob/main/dco.txt)
- [ ] Documentation under `docs/manual/` updated in this PR, if behavior, the REST API, configuration or the portal changed
- [ ] Tests added or updated; no existing SVC test weakened or deleted
- [ ] OpenSpec change archived as the final commit, if this PR implements one

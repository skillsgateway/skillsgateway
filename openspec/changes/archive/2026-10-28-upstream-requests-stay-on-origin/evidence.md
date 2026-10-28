# Evidence: upstream-requests-stay-on-origin

Source state: `754f8066` (tree `3729b1c0`), built on #751. When rebased onto
`main` after #751 merges, this change's diff must be identical; that is checked
before the PR opens. Every result below is from one fresh run after the last
code edit, on 2026-10-28.

Tier 2 (an outbound network boundary). Spec approval: the owner said to proceed
without stopping, to keep exposure short. Independent verification: not
performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_INGEST_0075) | Test | Seen failing first |
|---|---|---|
| A listing, anonymous and credentialed, redirected to another port is refused, and the other server receives nothing | `UpstreamRedirectTests.a_listing_redirected_to_another_origin_is_refused_before_it_is_followed` | yes: the other server received `/internal/x.git/info/refs?service=git-upload-pack` |
| A listing redirected to another host name is refused | `…a_listing_redirected_to_another_host_name_is_refused_before_it_is_followed` | no: added so the host check has a test of its own (M4) |
| A fetch redirected to another origin is refused, and the other server receives nothing | `…a_fetch_redirected_to_another_origin_is_refused_before_it_is_followed` | yes |
| A redirect within the origin is followed | `…a_redirect_within_the_origin_is_still_followed` | no: a control. M5 fails it |

**Changed assertions.** Two existing cross-port cases registered across a
redirect to another port and asserted that requests arrived there without the
credential. They now expect `502` and no request at the other port. Their SVCs'
claim, that no request outside the prefix carries the credential, still holds
and is now stricter. The same-origin path cases are unchanged.

- `UpstreamCredentialsIntegrationTests.a_redirect_to_another_port_of_the_same_host_carries_no_credential`
  (`SVC_GW_INGEST_0051`)
- `UpstreamGitHubAppIntegrationTests.a_redirect_to_another_port_carries_no_installation_token`
  (`SVC_GW_AUTH_0051`)

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1166, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:00 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.7m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `362/362 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.52 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-upstream-requests-stay-on-origin/mutants.sh` | `5 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-no-pin killed
M2-anonymous-unpinned killed
M3-port-ignored killed
M4-host-ignored killed
M5-path-pinned killed
5 mutants run, all killed
```

The first M1 survived because it was written wrongly: an `&&` mutant left the
host and port checks in force. It was rewritten to remove the whole condition.

## Known limits

- No address policy applies to the registered host itself (design D3). What
  that host may be is left to egress isolation and to the administrator who
  registered it.
- A scheme-only change (http to https on the same host and port) has no
  fixture of its own. The scheme comparison sits in the same condition that M1
  removes.

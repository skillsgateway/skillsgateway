# Evidence: invocation-adoption-metrics

**This change is design only.** It adds
[ADR 0016](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0016-client-invocation-telemetry-is-not-ingested.md),
its index entry, and this OpenSpec proposal. No Java, no TypeScript, no SQL, no
configuration, no `docs/reqstool/` entry. So the gates that exercise code have
nothing here to exercise, and running them would report on `main`, not on this
change.

**What was run** — the two gates this change can actually fail, fresh, after the
last edit:

```console
$ openspec validate --all --strict
…
✓ change/invocation-adoption-metrics
…
Totals: 32 passed, 0 failed (32 items)

$ mkdocs build --strict
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: …/site
INFO    -  Documentation built in 1.29 seconds
```

No retries were needed; neither command touches a container.

**What was not run locally, and why:** `./mvnw clean verify`,
`pnpm test:stories`, `pnpm e2e` and `reqstool status local -p docs/reqstool`. No
source, build or `docs/reqstool/` file is touched by this change, and the
container runtime was saturated by other work in flight at the time of the run.

**They were run by CI instead**, on the merge of this branch with `main` —
[run 34171033324](https://github.com/skillsgateway/skillsgateway/actions/runs/34171033324),
all fourteen checks green:

| Check | Result |
| --- | --- |
| Build & gates (`./mvnw clean verify`) | pass, 9m21s |
| Portal e2e | pass, 2m31s |
| Storybook tests | pass, 38s |
| Traceability & spec gates (reqstool, openspec) | pass, 13s |
| Documentation (strict) | pass |
| Breaking change detection | pass |
| CodeQL — actions, java-kotlin, javascript-typescript | pass |
| DCO, semantic PR title, single-file docs render, subsystem labels | pass |

That is a stronger result than a local run for a change of this shape: it proves
the design PR moves nothing, which is precisely its claim.

The reserved ids GW_0213, GW_0214 and GW_0215 are deliberately **not** entered on
`main`: a requirement with no `@Requirements` annotation and no passing
verification case fails the traceability gate by construction, so they land in
the implementing PR with the code that satisfies them (`tasks.md` §1).

**Commit under test:** the tip of `docs/invocation-metrics-design`, branched from
`331cf68` — the tip of `main` at the time of the run.

## Where the ADR's factual claims come from

The decision rests on two claims about a system outside this repository, so they
are cited rather than asserted. Both are read from the vendor's
`monitoring-usage` documentation as of 2026-09-08:

| Claim | What the documentation says |
| --- | --- |
| There is no metric that counts skill invocations | The metric instruments are `session.count`, `lines_of_code.count`, `pull_request.count`, `commit.count`, `cost.usage`, `token.usage`, `code_edit_tool.decision`, `active_time.total`. `skill.name` appears as an *attribute* on `cost.usage` / `token.usage` and on the `api_request` / `api_error` / `api_refusal` **events**; tool invocations are reachable only through the `tool_result` / `tool_decision` event stream. |
| The join key is redacted for gateway-served content | `skill.name`: "Built-in, bundled, user-defined, and official-marketplace plugin skill names appear verbatim. Third-party plugin skill names are replaced with `\"third-party\"`." `plugin.name`: the same rule. `marketplace.name`: "Only emitted for official-marketplace plugins. Absent otherwise." |
| The telemetry credential reaches every measured machine | Export is configured by `CLAUDE_CODE_ENABLE_TELEMETRY` plus `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_HEADERS`, set through managed settings. Managed settings lock the **destination** — they remove conflicting developer-set endpoint and credential variables at startup — and say nothing about what a process posts to that destination. |
| Identifying attributes are client-asserted | `user.email`: "Always included when available." `user.id`: "Random anonymous identifier generated on first run and persisted in `~/.claude.json` … Deleting the file produces a new unrelated value on next run." |

A reader who finds any of these no longer true should treat ADR 0016's rejected
option 3 — an aggregate-only pull from the organisation's own metrics backend —
as live, per that ADR's reopening conditions.

## What this change does not prove

That the presence report of GW_0213 is buildable at acceptable cost. `design.md`
argues it from two existing pieces — `SnapshotContentService.content` already
walks a snapshot's pinned tree, and a pinned commit's content is immutable so its
resolution caches forever — but nothing here measures it. That measurement is the
implementing PR's, and the design names the escalation if the argument turns out
to be wrong.

# Proposal: clamp-the-resolution-budgets

## Why

This change started as F8/§4's third PR — "the 14 mechanically safe leaves" —
and that premise did not survive the code.

**No configuration leaf is mechanically safe to remove.** Of 104 leaves, the
number whose accessor is never called anywhere in `src/main` is **zero**. Two
looked dead and were not: `retention.defaults.superseded` is read through
`supersededCriterionEnabled()` in the same record, and `mirror.sweep-initial-delay`
is read by Spring through the `@Scheduled` placeholder, where no accessor grep can
see it. The assessment's list also predates the sweep leases, which turned five of
its candidates into live readers.

Forty leaves are never *set* by the chart, `application.yaml`, the e2e compose
file, CI or any test — but that criterion does not survive either. It is
unreliable (the chart sets `…cache.dir` through an environment variable whose
relaxed-binding spelling collapses `object-store` to `OBJECTSTORE`), and being
unset is anyway the *start* of a judgement about whether operators need a dial,
not a safety property. Several entries on that list are obviously wrong
candidates: the static credentials pair is how you configure static credentials,
`allowed-hosts` is a security allowlist, `retention.marketplaces` is the
per-marketplace override map.

So there is no batch of safe cuts, and each individual cut is a separate argument
that belongs in its own change. **What is left in §4 is the item its own
correction identified as the honest one**, and it is a security improvement
rather than a tidy-up: the nine resolution budgets can be set to any value at
all.

`GW_INGEST_0026 — Resource-bounded source resolution` exists so that no manifest
the gateway will look at can exhaust the gateway. A bound settable to any value
is not that. It is a control an operator can switch off by typing a large number,
after which the gateway behaves exactly as an unbounded resolver while its own
configuration still claims a limit.

## What Changes

Each budget gains a **ceiling** — the furthest the gateway will honour a raise.
Above it, the ceiling is used, a warning names the key, the configured value and
the value in force, and the gateway starts normally.

| Budget | Default | Ceiling |
| --- | --- | --- |
| `max-received-bytes` | 50MB | 500MB |
| `max-inflated-bytes` | 200MB | 2GB |
| `max-closure-bytes` | 500MB | 5GB |
| `max-inflation-ratio` | 100 | 1000 |
| `max-objects` | 20000 | 200000 |
| `max-blob-bytes` | 10MB | 100MB |
| `max-tree-depth` | 32 | 256 |
| `max-redirects` | 3 | 10 |
| `deadline` | 5m | 30m |

Roughly an order of magnitude above each default, except where the bound's own
meaning caps it lower — a redirect chain longer than ten is a loop, a tree deeper
than 256 breaks the path-length assumptions every later walk makes, and a
resolution running past thirty minutes has outlived any request that could still
be waiting for it.

**A ceiling, not a constant.** Nine requirements say these are bounded *by
configuration*, and `GW_INGEST_0026.6`'s rationale argues that the blob bound
"has to be raisable on its own rather than only by raising every total beside
it". Turning them into constants — §4's actual proposal — contradicts both, and
would break three suites that shrink a bound so a small fixture can exceed it
rather than building a fixture large enough to exceed a production default. A
ceiling keeps every one of those properties: lowering is never clamped, raising
works, and raising one bound still does not require raising the others.

**Clamped and logged, not refused.** A stale number in a manifest should not be
an outage. The substitution is only defensible because it is recorded — an
operator who set a number and got a different one has no other way to find out,
and would otherwise meet it as a resolution refusing content they expected to
fit.

## Impact

- **No behaviour change for any deployment inside the ceilings**, which is every
  deployment: no chart, compose file, CI job or test sets any budget above its
  default.
- **New requirement** `GW_INGEST_0031` and `SVC_GW_INGEST_0031`. The ceiling is an
  obligation no existing requirement states — `GW_INGEST_0026` says the bounds are
  configured and enforced, not how far a configured value is honoured — so
  stretching its SVC to cover this would have made the traceability say something
  it does not.
- **No leaf removed.** The ratchet stays at 104.

## Out of scope

Cutting knobs. The analysis above says which criterion each candidate fails, and
the ratchet from the guardrail change is what makes the surface's size a decision
from here on. A cut worth making is worth its own argument.

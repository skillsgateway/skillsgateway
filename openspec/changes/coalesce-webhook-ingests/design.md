# Design: coalesce-webhook-ingests

## Context

See proposal.md, Why. `SyncService.queueWebhookIngest` submitted one
`supplyAsync` per call to the single-threaded `syncWebhookExecutor`.

## Decisions

### D1 — One queued future per marketplace id

The service keeps a `ConcurrentHashMap` from marketplace id to the queued
future. `computeIfAbsent` returns it to any trigger that finds one. The task
removes its own entry as its first action. Only `computeIfAbsent` adds an
entry, and only when none is present, so the entry removed is always the
task's own.

### D2 — Clear on start, not on completion

Clearing on completion would make a push that lands during a running fetch
join an ingest that has already read the old tip. Clearing on start queues one
follow-up, which fetches the newer tip.

### D3 — No explicit queue bound

Coalescing already bounds the queue by the number of webhook-mode
marketplaces, which is estate size and set by administrators. A second bound
would add a configuration leaf or a refusal path and protect nothing further.

## Risks / Trade-offs

- [A coalesced trigger shares the first trigger's `Marketplace` value] → The
  ingest reads only the id and the registered URL, which a trigger never
  changes.

# What is in this directory

Every subdirectory here is a change that is **not finished**. Once a change
ships, `openspec archive` moves it into `archive/` and folds its requirements
into `openspec/specs/`.

That was the theory. In practice the listing said nothing: on 2026-09-09 this
directory held eight entries, and a reader could not tell that four of them had
shipped months earlier, or that the other four were waiting on the owner rather
than on effort. A live defect, a finished argument and eighty tasks of unstarted
scope all looked identical — which is what the 2026-09-08 architecture
assessment meant when it said the process has gates that say *how* and none that
can say *no*.

**So: every entry here carries a status header saying what it is waiting for.**
If you add a change, it starts active and needs no header. If it stops, say so
at the top of its `proposal.md`, in one paragraph, naming the thing it waits on.

## Now

| Change | State | Waiting on |
| --- | --- | --- |
| `corpus-aware-vetting` | parked | **ADR 0015** acceptance, and its four open questions |
| `estate-import-export` | parked | **ADR 0014** acceptance |
| `invocation-adoption-metrics` | parked | **ADR 0016** acceptance |
| `virtual-catalogs` | parked | nothing external — its defect half shipped; the rest is feature work |

Three parked changes, three ADRs sitting at *Proposed*. **The backlog is not
stalled on capacity. It is stalled on three decisions**, and any of them can be
made without writing code.

## Two things a reader gets wrong

**`invocation-adoption-metrics` is not a decision not to build.** ADR 0016
refuses *client-reported* telemetry — forgeable by the population being measured
— but explicitly rejected doing nothing, and chose to build presence instead.
Two of its twenty-seven tasks encode the refusal; the rest are constructive. The
finished "no" is the ADR, which stands alone.

**The `GW_NNNN` ids in every parked proposal are drafting artifacts.** ADR 0018
retired the flat sequence. None was ever entered in `docs/reqstool/`, so there
is nothing to clean up — but each proposal's section 1 still instructs an
implementer to use the old format, and is wrong.

## One obligation that lives nowhere else

`docs/manual/architecture.md` §9 still offers install-inventory enrichment
"optionally … with client OTel telemetry", and §13 still lists client telemetry
inventory. Both are correct **today** and become wrong the moment ADR 0016 is
accepted. Only `invocation-adoption-metrics`' task list tracks the rewrite.

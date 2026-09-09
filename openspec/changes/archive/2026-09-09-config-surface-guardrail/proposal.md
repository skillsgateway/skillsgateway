# Proposal: config-surface-guardrail

## Why

The gateway offers 105 configuration leaves. Nothing counts them, nothing
notices when the number grows, and adding one costs a single line in a nested
record — so the surface reached its present size without anybody deciding it
should. Every leaf is a name an operator may set, a default that has to stay
defensible, a row in the reference and a combination the suite is supposed to
cover.

**The assessment that asked for this counted 127 by hand.** That figure does not
reproduce; the measured number is 105, and all 105 are documented in the
configuration reference (checked leaf by leaf against its tables). The
discrepancy is the argument for the counter rather than a detail to reconcile: a
surface nobody can count twice the same way is a surface nobody is deciding the
size of.

The second half is the other end of a leaf's life. Removing one is currently a
deletion plus a release note, except for `skills-gateway.roles.enabled`, which
got a real startup refusal in `RoleBootstrapGuard` because that removal turned
authorization off. That refusal is the right behaviour and the wrong shape: it is
one hand-written check, and the check that gets forgotten is always the one
somebody had to remember to add.

## What Changes

### A leaf ratchet

`ConfigSurfaceBudgetTests`, in the style of `ContextBudgetTests`: walk the record
components of `SkillsGatewayProperties`, count the leaves, fail above the budget,
write the list to `target/config-surface.txt`. No Spring context, no binder —
deliberately the crudest counter that cannot disagree with the binder about what
is settable.

The budget is a ratchet, not a target. A knob that is genuinely needed still gets
added; what changes is that adding one is a visible act with a number attached.

### `RemovedPropertyGuard`

One pass over one map, iterated at startup — not one hand-written check per
property. Each entry carries a **disposition**:

- **`REFUSE`** for a property whose removal an operator could read as the
  opposite of what they asked for: anything that used to switch a control off or
  raise a bound. A gateway quietly doing the safer thing is still doing something
  other than what the manifest says, and that is invisible from inside the
  deployment.
- **`WARN`** for a property whose removal costs latency or throughput and nothing
  else — an interval, a batch size. Refusing there turns a leftover line into an
  outage, which is a worse trade than a log line.

`skills-gateway.roles.enabled` moves in as the first entry, keeping its message
and its behaviour. Relaxed binding means one `containsProperty` per entry still
covers every spelling the framework would resolve, so the list never has to be
kept in step with a second list of spellings. Every refusal is reported together:
an operator upgrading across several versions has more than one stale line, and a
guard that stops at the first turns one edit into a sequence of failed starts.

`WARN` ships with no entry using it, and is tested on the mechanism. A
disposition first exercised by the change that needs it is a disposition nobody
reviewed.

### The split follows the requirement boundary

`RoleBootstrapGuard` carried both `GW_AUTH_0026 — A gateway with no administrator
refuses to start` and `GW_AUTH_0027 — The removed enforcement property is refused
rather than ignored`. Those are two requirements and now two classes; the guard
keeps `GW_AUTH_0026`.

`RoleBootstrapGuard` takes `RemovedPropertyGuard` as a **constructor parameter**.
Both throw from their constructors, so which message an operator sees would
otherwise be whichever bean the container built first — and it builds them in the
order that happens to be right, so the behavioural test passes with or without
the dependency. The parameter is what makes the order a fact, and a structural
assertion is what notices if somebody deletes it as unused.

## Impact

- **No behaviour change.** The same property is refused with the same effect;
  only the shape and the message's assembly moved.
- **No new configuration**, and no requirement text changes. `GW_AUTH_0027` still
  describes exactly what ships.
- **A stale javadoc corrected.** `SecurityConfig` still explained why the machine
  path "does not consult `skills-gateway.roles.enabled`", a flag removed in #210.

## Out of scope

The cuts. This is the guardrail, alone and first, because nothing today notices
the surface growing — that is how it got here. Each later cut lowers the budget
and populates the map, and each is its own change.

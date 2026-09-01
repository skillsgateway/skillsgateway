# ADR 0009 — Admin override of vetting automation (the cockpit model)

*Accepted, 2026-09-01.*

## Context

Issue [#223](https://github.com/skillsgateway/skillsgateway/issues/223) asks for
a deliberate reversal of two stances the codebase had taken on purpose:

- `SkillsGatewayProperties.Vetting` states there is **deliberately no
  enable/disable switch** for the vetting chain: "an operator who could switch
  it off would be switching off the record rather than the gate."
- `ApprovalService.approve` states there is **no blanket override**: "Approving
  past objecting connectors means recording a scoped, expiring waiver for each
  blocking finding first." The waiver mechanism ([snapshot-vetting],
  [vetting-waivers]) is the only sanctioned way past a block.

The governing principle is the airline cockpit: automation can be **disconnected
by the captain**. The vetting chain is automation; an administrator must be able
to override it deliberately — but every override is **admin-only** and **duly
noted in the audit ledger**. This is the human-in-command escape hatch, not a
way to route around governance quietly. The admin role model was hardened to
unconditional enforcement in
[#210](https://github.com/skillsgateway/skillsgateway/issues/210) (GW_0138),
which is what makes "admin-only" a load-bearing guarantee rather than a
configurable one.

Two mechanisms are in scope, and they are different kinds of act:

1. **Enable/disable a built-in connector**, globally or per marketplace — a
   *standing* administrative decision about which controls run.
2. **Approve despite a blocked outcome** — a *one-off* administrative decision to
   take responsibility for one snapshot the chain objects to.

## Decision

**Both mechanisms exist, both are admin-only, both are audited distinctly, and
both are fail-loud. Neither weakens the gate silently.**

### 1. Connector enable/disable (GW_0143)

An administrator may switch a built-in connector off — globally or for one
marketplace — through `PUT /api/vetting/connectors/{name}/toggle`. A disabled
connector is **not run** at ingestion or re-vetting; in its place the chain
records a distinct `disabled` verdict, so the disablement is part of the run's
evidence rather than a silently shorter chain. The fail-closed aggregation
(GW_0038) is extended, not loosened: a `disabled` verdict is neither clearing nor
blocking, **and a run still requires at least one clearing verdict to clear** —
so disabling every connector leaves a run *blocked*, never cleared. The switch
can therefore never become a blanket approval by omission.

This reverses the "no enable/disable switch" stance narrowly. The original
argument was that a global kill switch only buys "a blocked estate with no
findings." That is answered by two properties the original switch lacked: the
disablement is **recorded per run** (fail-loud), and disabling everything still
**blocks** (no free pass). What an operator gains is the ability to answer a
too-noisy or wrong connector for one marketplace without redeploying.

### 2. Override of a blocked outcome (GW_0142)

An administrator — and only an administrator — may approve a held or revoked
snapshot whose effective outcome is blocked, by setting `overrideVetting` with a
**reason** on the approve endpoint. The override **lifts only the vetting gate**:
the policy gate, the minimum release age and the four-eyes rule all still run, so
the override is not a skeleton key. It is refused without the admin role and
refused without a reason. It writes a distinct ledger event,
`snapshot-approved-over-vetting-failure`, naming the administrator, the reason
and the blocking verdicts, and it records a standing marker on the snapshot
(`snapshot_vetting_overrides`) surfaced on the vetting read surface — so an
override is never indistinguishable from an approval the chain cleared.

### Override versus waiver

The override does **not** replace waivers, and the two are deliberately different
tools:

| | Waiver (GW_0044–0048) | Override (GW_0142) |
| --- | --- | --- |
| Who | any reviewer/approver | administrator only |
| Scope | one finding, one rule, scoped and **expiring** | one snapshot, the whole blocked outcome, one-off |
| Meaning | "this specific finding is an accepted risk until *date*" | "I, an administrator, take responsibility for shipping this blocked snapshot now" |
| Trail | waiver row + `waiver-*` events | distinct `snapshot-approved-over-vetting-failure` event + standing marker |

The waiver is the narrow, attributable, temporary acceptance that keeps the
common case reviewable. The override is the rare captain's act for when a whole
outcome must be shipped and no per-finding waiver is the right instrument. Making
the override its own loud, admin-only, reason-bearing event is what stops it from
being the silent bypass the "no blanket override" stance was protecting against.

## Consequences

- The vetting trust boundary gains two new admin-only entry points; both carry
  adversarial/negative tests (non-admin refused; trail always written; disabling
  everything still blocks) per the `.claude/skills/old-coder` discipline.
- Architecture invariant 3 (every admin action lands in the append-only ledger)
  is upheld and strengthened: the override and every toggle are first-class,
  distinctly-typed ledger events.
- Connector toggles are **API-managed runtime state**. For this change they are
  API-only, like personal access tokens: folding them into the declarative
  estate (`skills-gateway.estate.*`) is a worthwhile follow-up but requires
  changing the `Estate` record's shape and every call site, which is orthogonal
  churn better done in its own change. This is called out in the change's design
  as the deliberate, temporary API-only choice CLAUDE.md's estate obligation
  permits.
- The portal surfaces (a badge for "approved over vetting failure", the connector
  on/off controls) are a follow-up integrated with
  [#224](https://github.com/skillsgateway/skillsgateway/issues/224); the backend
  exposes everything those surfaces need.

[snapshot-vetting]: https://github.com/skillsgateway/skillsgateway/blob/main/openspec/specs/snapshot-vetting/spec.md
[vetting-waivers]: https://github.com/skillsgateway/skillsgateway/blob/main/openspec/specs/vetting-waivers/spec.md

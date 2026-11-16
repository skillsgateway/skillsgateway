# ADR 0023 — Automatic approval of internal marketplaces

*Accepted, 2026-11-15. Supersedes, for marketplaces an administrator opts in,
the position recorded in [ADR 0006](0006-embedded-cel-for-policy-rules.md)
that auto-approval stays parked because "nothing is served that a person did
not approve". Decides the question
[ADR 0021](0021-risk-is-expressed-as-findings-not-tiers.md) left open, with a
findings predicate and no tiers. OpenSpec change `marketplace-auto-approval`.*

## Context

Every snapshot is held until a person approves it. The first organisation
preparing to adopt the gateway uses it as it uses a repository manager such as
Artifactory or Nexus: one governed source for mostly its own skills and
plugins. For that use, a person's click on each clean update of its own
repositories is pure cost, and it is the reason they cannot adopt.

Two facts make this more than a switch:

- **Internal is not safe.** An internal repository can be compromised, a
  credential can be stolen, and in an agent skill Markdown is instructions.
- **Internal content pulls in external content.** A marketplace's closure can
  graft external sources fetched at a floating `HEAD`, and their URLs can
  redirect within a host.

ADR 0021 named this decision as what would reopen the tier question, and named
the predicate to try first: a findings threshold, not a tier.

## Options considered

### Option A — Keep every approval human

Rejected. It is the status quo, and it is the reason the adopter cannot adopt.
The rest of the gateway's controls (vetting, policy, collision, re-vetting,
revocation) do not depend on a person clicking, so the click is the only thing
this option protects, and on a clean internal update it decides nothing.

### Option B — Risk tiers, with T0 auto-approving

Rejected, for the reasons in ADR 0021: a tier restates findings in a second,
lossy vocabulary, and "Markdown only" is not "nothing runs".

### Option C — A per-marketplace automatic approval setting with a narrow, fail-closed predicate (chosen)

An administrator opts a marketplace into automatic approval for a limited
time. A snapshot of it is approved by a system actor only when a fixed
predicate over its findings, closure and executable surface holds, and only
through the same approval decision a person's goes through. Anything in doubt
stays held for a person.

## Decision

- **Opt-in, dated and accountable.** A marketplace's automatic approval is
  off by default. Turning it on records who did it, why, and an expiry of at
  most 90 days. Turning it on is subject to four-eyes: the registering
  administrator is conflicted. A deployment-wide mode (`off`, `dry-run`, `on`)
  defaults to `off`.
- **Internal is the operator's statement.** A marketplace is internal when it
  is hosted or its URL falls under an operator-configured prefix on whole path
  segments. A marketplace outside the prefixes needs an administrator exemption
  with its own 90-day expiry, which is never declarable in the estate.
- **Trust never travels.** A snapshot with any closure member stays held,
  whatever the member's URL.
- **The predicate.** The vetting chain is current and clears; every required
  vetter ran; only waivers the served baseline already carried can clear a
  high finding; the executable surface is unchanged from the served baseline
  and every read of it completed; every added path is on a short inert list;
  only the newest snapshot is a candidate.
- **Same path, same evidence.** The automatic approver calls the one approval
  decision, so every existing gate applies unchanged, and one recorder writes
  the same ledger entries and webhook for both paths. The automatic approver is
  a typed system actor, and its entry carries what allowed it.
- **Unattended recall.** Re-vetting revokes an automatically approved snapshot
  on new blocking evidence whatever the global re-vetting mode, and automatic
  approval acts only while re-vetting is enabled. An administrator can withdraw
  every automatic approval since a given time in one request.

### The four-eyes exemption for the estate reconciler

Automatic approval declared in the estate is applied by `config-reconciler`. Its
separation of duties is the review of the estate repository, which happens
outside the gateway, so the reconciler is exempt from the four-eyes check on
the grant. It is exempt from that check only: it is not added to
`FourEyesGate`'s non-human actors, which would also change approvals.

### Why re-vetting enforces only for automatic approvals

Requiring the global re-vetting mode to be `enforce` would let a gateway
upgrade that adds a noisy rule mass-withdraw content people vouched for.
Content no person approved has no one to wait for, so it alone is revoked
regardless of the mode; content a person approved keeps following the mode.

### Which vetters must have run

A switched-off vetter neither blocks nor counts (`GW_VETTING_0029 — The vetter
on/off switch never becomes a blanket approval`), so with every vetter but one
switched off a single passing check would clear the chain. By default every
vetter is required. An operator can name the required ones in
`skills-gateway.approval.auto.required-vetters`, gateway-wide, so that the
administrator turning automatic approval on cannot lower the bar for that marketplace.
Startup refuses an empty list and an unknown name. Each automatic approval's
ledger entry names the vetters that were switched off.

## Accepted residual risk

In an agent skill, Markdown is instructions. A changed `SKILL.md` that raises
no finding is approved automatically. A compromised internal repository can
therefore publish a malicious instruction-only change without a person, until
re-vetting or a person notices and it is withdrawn.

The mitigations are the ones above: no external sources, the executable
surface rule, the whole-chain rule, baseline-only waivers, every existing
gate, automatic revocation on re-vetting, bulk withdrawal, and the mode switch.
A dry run is how an adopter measures how often an instruction-only change
would publish before turning automatic approval on.

## Consequences

- The product's first principle becomes "nothing is served that a person did
  not approve, or that an administrator opted into automatic approval of under
  a predicate no person needed to judge". Every automatic approval is on the
  ledger as such.
- A per-snapshot tier or risk class is still not computed (ADR 0021 stands):
  the predicate is over findings and over what changed.
- Release-age promotion of an automatically approvable snapshot rides the
  re-vetting tick, so its lag is at most `revet.interval`.
- A later change may accept an external member whose `(clone_url, tree_sha)` a
  person already approved in the same marketplace. Until then, any member keeps
  a snapshot with a person.

# Signed provenance stays in Phase 3, with named pull-forward triggers

- **Status:** proposed
- **Date:** 2026-08-18
- **Deciders:** jimisola

## Context and Problem Statement

Internal review RB-REV-2026-08 (the skills-marketplace proposal review) puts
signed provenance in the Phase-1 supply-chain prerequisite bucket: protected
distribution channel, mandatory review, secrets scanning, **and provenance**
before anything is installed by default. The gateway's roadmap
(architecture §7) schedules signed in-toto/Sigstore attestations for Phase 3.
Issue [#84](https://github.com/skillsgateway/skillsgateway/issues/84) asks
whether that deferral still holds, and demands the answer be decided rather
than defaulted.

The question is not whether provenance exists — it is whether the existing
*recorded* provenance must become *signed* provenance now.

What exists today:

- **Recorded provenance, end to end.** Content-addressed snapshots pin the
  bytes to the upstream commit SHA; the vetting chain attaches every
  connector verdict to the snapshot permanently; the approval writes who
  approved what, against which evidence; the append-only ledger records every
  fetch. The chain upstream SHA → vetting verdicts → approval → published
  artifact is complete and queryable.
- **One trust domain.** Everything is served *from* the gateway: clients
  fetch only what the publisher produced, and the publisher only publishes
  what `ApprovalService` approved. There is no hop where an artifact travels
  outside the gateway and needs to carry its own proof.

What signed attestations would add: proof that is verifiable *independently
of the gateway operator and its database* — each pipeline step captured as a
cryptographically signed statement (in-toto/SLSA-style, Sigstore-signed) that
travels with the artifact.

## Decision

**Signed attestations remain in Phase 3.** The review's demand is honored by
the recorded-provenance chain plus this ADR making the residual risk explicit,
not by pulling the signing machinery forward.

Rationale:

1. **The threat signed provenance addresses is not in the current threat
   model's scope.** Signing defends against (a) tampering with the gateway's
   own database by a privileged insider or attacker, and (b) consumers who
   receive artifacts outside the gateway's serving path. Today neither hop
   exists: the façade serves only publisher output, and the DB is inside the
   same trust boundary as the services that would do the signing — a
   compromised gateway could sign lies just as easily as it could write them.
   Signing adds real value only once verification happens *elsewhere*.
2. **Key management is the actual cost.** A signature is only as trustworthy
   as its key custody. Doing this properly means signing keys or Sigstore
   infrastructure, rotation, and a verification story on the consumer side —
   standing operational surface that a single-trust-domain deployment pays
   for without consuming.
3. **The cheap part is already done.** Content addressing makes the artifact
   ↔ SHA binding tamper-evident by construction; the expensive part
   (operator-independent verification) is exactly the part with no consumer
   today.

## Pull-forward triggers

Any one of these reopens the decision and moves attestations into the active
roadmap; they are the conditions under which the rationale above stops
holding:

| Trigger | Why it flips the decision |
| --- | --- |
| **OCI re-publication or repository-manager federation ships** (Phase 3 roadmap items) | Artifacts leave the gateway's serving path — the artifact must carry its own proof |
| **An auditor or regulator requires operator-independent evidence** (e.g. a DORA/ICT examination asks "prove your own DB wasn't altered") | Recorded provenance inside the operator's trust domain no longer satisfies the examiner |
| **Multi-instance or air-gapped promotion between gateways** | Promotion between trust domains is exactly an attestation handoff |
| **A consumer-side verification tool materializes** (clients or CI verifying SLSA/in-toto bundles for skills) | The verification side exists, so the signing side has a consumer |

## Consequences

- Architecture §7's Phase-3 placement of in-toto/Sigstore attestations stands,
  now backed by a recorded decision instead of a default.
- The compliance answer to RB-REV-2026-08's provenance prerequisite is:
  recorded provenance (content addressing + ledger) with this ADR stating the
  residual risk and its triggers — not "planned later" with no rationale.
- When a trigger fires, the follow-up starts from this ADR: scope is signing
  the existing chain (upstream SHA → vetting verdicts → approval →
  published artifact), not designing a new one — the data model already
  captures every statement an attestation would sign.
- Closes [#84](https://github.com/skillsgateway/skillsgateway/issues/84).

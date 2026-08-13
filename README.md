# Skills Gateway

An enterprise gateway for git-distributed AI agent skill marketplaces
(Claude Code plugins, GitHub Copilot, Cursor) — the missing analogue of
Artifactory/Nexus for the part of the skills ecosystem that never touches a
package manager.

Skill marketplaces are git repositories cloned straight from the public
internet: no vetting gate, no immutable versions, no inventory, no audit
trail. The Skills Gateway is the choke point: it ingests upstream
marketplaces into quarantine, holds every snapshot until it passes vetting,
and serves only approved, SHA-pinned content to unmodified git clients —
with every fetch in an append-only audit ledger.

**Status: pre-alpha.** The architecture and requirements are established;
the product implementation (Java 25 / Spring Boot 4 / JGit, GraalVM
native-image at release) is being scaffolded. A validated Python prototype
of the full loop lives in the sibling `skills-gateway-python-mvp` repository.

## Documentation

Arriving on the scaffold branch:

- `ARCHITECTURE.md` — the full architecture (threat model, connector-based
  vetting, two-repo promotion, git façade, observability, roadmap)
- `docs/decisions/` — ADRs (language, toolchain)
- `docs/reqstool/` — requirements and verification cases
  (reqstool-traceable, `GW_*` / `SVC_GW_*`)
- `openspec/` — spec-driven change workflow (OpenSpec)

## License

Apache License 2.0 — see [LICENSE](LICENSE).

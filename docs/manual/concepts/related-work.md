# Related work

Skills Gateway is not the only product occupying the "central place for AI
agent skills" space. This page positions it against the closest-named
alternative, so the difference in trust model is explicit rather than implied
by the name.

## LiteLLM Skills Gateway

[LiteLLM's Skills Gateway](https://docs.litellm.ai/docs/skills_gateway) is a
centralized **registry** for Claude Code skills: teams register a GitHub URL
via API or UI, the system auto-detects the skill name (including nested
subdirectories), an admin flips an *enable* endpoint, and the skill appears on
an unauthenticated hub page and in a generated marketplace manifest that
Claude Code clients add with `/plugin marketplace add`. Skills carry semver,
descriptions, keywords, and a domain/namespace hierarchy.

It solves the **discovery and distribution** half of the problem. It is a
registry of *pointers*: the content authority remains the upstream repository,
and "publishing" gates the visibility of a listing, not the bytes a client
receives.

## The difference in one table

| Concern | LiteLLM Skills Gateway | Skills Gateway |
| --- | --- | --- |
| What is registered | A pointer to an upstream GitHub URL | The upstream URL, ingested into a quarantine repository |
| What clients receive | Content fetched from upstream at install time | Bytes from a SHA-pinned, human-approved snapshot served by the read-only facade |
| Publish gate | Listing visibility (enable/disable) | Human approval of a specific snapshot; held updates never displace the approved one |
| Rug-pull protection | None — upstream refs stay mutable and authoritative | The core of the design: [snapshots and held updates](snapshots-and-ledger.md) |
| Revocation | Disable the listing; already-installed content unaffected | Revocation unpublishes the served ref itself |
| Audit | Not described | Append-only ledger of every fetch and administrative action |
| Facade access | Unauthenticated public hub | PAT-only facade — a deliberate [trust boundary](trust-boundaries.md) |
| Single marketplace URL | Generated manifest of external pointers | The [virtual catalog](../guides/virtual-catalog.md), strictly derived from approved-and-served snapshots, self-contained relative sources only |
| Skill metadata for discovery | Semver, keywords, domains/namespaces per skill | Marketplace-granular catalog; skill-level browse is not a current capability |

## What this means

The two products are complementary ends of the same space. A registry answers
"where do I find skills?"; Skills Gateway answers "how do I know the skills my
developers install are the ones we reviewed — and stay that way?". A registry
of pointers inherits the [threat model](../index.md#threat-model-in-brief)
unmitigated: a compromised or mutated upstream flows straight to every
consumer, because nothing sits between the listing and the clone.

The one capability the comparison surfaces as a genuine gap on this side is
**skill-level discovery metadata** (categories, keywords, per-skill search in
the portal). That is a product feature, not an architectural difference, and
it composes cleanly on top of the existing model if the estate grows large
enough to need it.

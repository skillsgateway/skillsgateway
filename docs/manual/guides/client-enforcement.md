# Making the gateway the only door

The gateway can only govern what clients fetch *through it*. Nothing on the
server side stops a developer from adding an upstream marketplace directly —
that half of the control lives in client configuration, network policy, and
CI. This guide collects the recommended settings per client, with pointers to
each vendor's own documentation. It is defense in depth: apply what your
fleet management supports, and treat none of it as the sole control
(see [architecture §8](../architecture.md#8-enforcement-making-it-the-only-path)).

!!! warning "Vendor-controlled and time-sensitive"

    Every setting on this page is implemented by the client vendor, not by the
    gateway, and behavior changes with client releases. Verify current
    behavior with your vendor account team before relying on any of these as a
    hard control — in particular, confirm in writing how managed settings
    behave for the client builds your fleet actually runs (desktop, terminal
    CLI, and IDE surfaces have differed historically).

## Claude Code

Claude Code supports fleet-managed settings distributed via MDM that users
cannot override. The relevant keys
([managed settings documentation](https://code.claude.com/docs/en/settings)):

| Setting | Effect |
| --- | --- |
| `strictKnownMarketplaces` | Restricts `claude plugin marketplace add` to an explicit allowlist — set it to the gateway's marketplace URLs and ad-hoc additions are blocked client-side |
| `blockedMarketplaces` | Denylist with owner wildcards, for blocking known-bad sources even where the strict allowlist is not used |
| `extraKnownMarketplaces` + `enabledPlugins` | Pre-registers the gateway's marketplaces and installs the approved set fleet-wide |

A minimal managed-settings fragment pointing a fleet at the gateway:

```json
{
  "strictKnownMarketplaces": [
    "https://skills.example.corp/git/catalog"
  ]
}
```

Pointing the allowlist at the [virtual catalog](virtual-catalog.md) keeps it
stable: newly approved marketplaces appear inside the catalog without a fleet
configuration change.

## Cursor

Cursor's enterprise controls offer an equivalent posture
([plugins documentation](https://cursor.com/docs/plugins)):

- **Public Marketplace Allowlist** — restricts which public-marketplace
  plugins members may use; an empty allowlist disables the public marketplace
  for the organization.
- **Team marketplace** — the curated internal channel; approved content is
  distributed through it instead of public sources, with access scoped by
  Organization Groups.
- **Network allowlists** (Enterprise plan) — org-wide egress policy applied
  to agent sandbox sessions, which can be scoped to the gateway host.

## GitHub Copilot CLI

Copilot's enterprise controls cover MCP servers, not skill sources: an
[MCP server allowlist](https://docs.github.com/en/enterprise-cloud@latest/copilot/how-tos/administer-copilot/manage-mcp-usage/configure-enterprise-allowlist)
in the enterprise `managed-settings.json` and a
[registry-only MCP policy](https://docs.github.com/en/copilot/how-tos/administer-copilot/manage-mcp-usage/configure-mcp-server-access)
that applies to
[Copilot CLI](https://docs.github.com/en/copilot/how-tos/copilot-cli/administer-copilot-cli-for-your-enterprise).
There is currently no setting restricting which skill repositories or
marketplaces a Copilot CLI user can add — for skills distribution, network
egress policy carries the load.

## Network egress and CI

Client settings only reach managed machines. Two backstops apply regardless
of client:

- **Egress policy** — block (or at minimum alert on) direct git/HTTPS access
  from developer machines and CI to known marketplace hosts. Blocked attempts
  are themselves a signal worth forwarding to the SIEM.
- **CI as a backstop** — pipelines resolve skills only through the gateway;
  builds referencing unapproved sources fail. This catches what laptop-level
  controls miss before anything ships.

## Context

The sidebar's **Tools** group holds one outbound link today (`/docs`, the API
reference). Two more outbound destinations are wanted: the manual and the source
repository. The only decisions worth recording are where they live and whether
their addresses are configurable.

## Goals / Non-Goals

- **Goal:** the manual is one click from any portal page.
- **Goal:** the outbound affordance is in the accessibility tree, not only in an
  icon.
- **Non-Goal:** a configurable documentation base. See the decision below.
- **Non-Goal:** in-product help, embedded docs, or deep links from a page to the
  matching manual section. That is a larger idea and is not proposed here.

## Decisions

**The addresses are constants, not configuration.** `mkdocs.yml` already declares
`site_url: https://skillsgateway.github.io/skillsgateway/` and `repo_url:
https://github.com/skillsgateway/skillsgateway`; ADR 0004 — the skillsgateway org
and coordinates fixes them. Making them settable would add two configuration
leaves against `ConfigSurfaceBudgetTests` to serve a case nobody has: a
deployment that mirrors the manual internally. Per the stop rule, that case can
be admitted when someone has it, and it would then be one leaf (a docs base),
not two.

The visible consequence is that an air-gapped deployment shows two links that do
not resolve. That is the same failure as the existing `/docs` link behind a
proxy that strips it, and cheaper to live with than a configuration surface.

**They stay in the Tools group, not the user menu.** Tools is already the
portal's answer to "things that are not estate navigation", and the group label
does not need to change to hold them.

**The affordance is in the name.** An external-link icon is not announced by a
screen reader, so each link's accessible name says it opens a new tab; the icon
is `aria-hidden` decoration, matching the icon treatment everywhere else in the
sidebar. `target="_blank"` always ships with `rel="noopener noreferrer"`.

## Risks / Trade-offs

- A link out is a link away from a governance surface mid-review. Accepted: the
  reviewer leaving to read the manual is the behavior being enabled, and it opens
  in a new tab rather than replacing the page they were working on.

## Migration Plan

None. Additive presentation, no stored state, no API.

## Open Questions

None.

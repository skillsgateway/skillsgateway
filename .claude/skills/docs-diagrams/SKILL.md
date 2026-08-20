---
name: docs-diagrams
description: Regenerate the docs site's editorial SVG diagrams from their Mermaid sources in docs/diagrams/ using the diagram-design system with the Skills Gateway skin. Load whenever a .mmd source changes, a new headline diagram is added, or a docs PR touches a page that embeds a dd-diagram SVG pair.
---

# Docs diagrams — Mermaid SSOT, diagram-design SVGs

Headline diagrams on the docs site are hand-crafted SVGs in the
[diagram-design](https://github.com/cathrynlavery/diagram-design) editorial
system, generated from Mermaid sources. The split:

- **`docs/diagrams/*.mmd`** — the source of truth. Diffable, reviewed in PRs,
  outside `docs_dir` so MkDocs never publishes it.
- **`docs/manual/assets/diagrams/<name>-light.svg` + `<name>-dark.svg`** —
  generated build products. Never hand-edit beyond regeneration.
- The embedding page inlines both via `pymdownx.snippets` inside
  `dd-diagram dd-light` / `dd-dark` divs; `assets/extra.css` switches them on
  Material's `data-md-color-scheme` and loads the fonts (inline SVG inherits
  page fonts — `<img>` embedding would silently lose them).

**Scope: headline diagrams only** — currently `concepts/lifecycle.md`'s
flowchart. Workaday diagrams (sequence diagrams, guides) stay plain
```` ```mermaid ```` blocks. A diagram earns the treatment when it carries a
page's thesis, not because it exists.

## The rule

**Any PR that changes a `docs/diagrams/*.mmd` regenerates its SVG pair in the
same PR** — the docs-in-same-PR rule applied to diagrams. Conversely, never
change a generated SVG's content without changing its `.mmd` first.

## Regenerating

1. Get the upstream skill: `/plugin marketplace add cathrynlavery/diagram-design`
   and install `diagram-design@diagram-design` (or clone the repo and read
   `skills/diagram-design/SKILL.md`). Version pinned by this workflow: 2.5.
2. Follow its **import-mermaid** flow against the changed `.mmd`
   (`scripts/mermaid_extract.py`, then redraw — never mimic Mermaid's layout).
   Dials: format `svg` (as the inline pair below), size `doc-wide`, detail
   `balanced`, audience `mixed`.
3. Apply the **Skills Gateway skin** (below), not the shipped default.
4. Produce both variants with **variant-scoped CSS class prefixes**
   (`ddl-` light, `ddd-` dark) and variant-suffixed marker/`title`/`desc` ids
   — both SVGs are inlined into one HTML document, so anything global collides.
   Each SVG paints its own paper `<rect>` and carries `role="img"` +
   `aria-labelledby` per the upstream accessible-SVG contract.
5. Verify: upstream `scripts/self_check.py` and `verify-geometry.py` must both
   pass; then `mkdocs build --strict`.
6. Report the fidelity ledger (merges/collapses/drops vs the `.mmd`) in the PR.

## Skills Gateway skin

Mapped from the portal's design system (`--primary`
`oklch(0.541 0.247 293.01)`); the docs Material palette is deep purple, so the
accent matches both.

| Role | Light | Dark |
| --- | --- | --- |
| paper | `#f7f5fa` | `#201c2a` |
| node-fill | `#ffffff` | `#2c2739` |
| ink | `#26222f` | `#f1eef8` |
| muted | `#5c576e` | `#b3acc6` |
| soft | `#837d94` | `#8d86a1` |
| rule | `rgba(38,34,47,0.12)` | `rgba(241,238,248,0.12)` |
| rule-solid | `#c9c4d6` | `rgba(241,238,248,0.24)` |
| accent | `#7c3aed` | `#a78bfa` |
| accent-tint | `rgba(124,58,237,0.08)` | `rgba(167,139,250,0.10)` |
| accent-soft (security/boundary) | `rgba(124,58,237,0.5)` | `rgba(167,139,250,0.5)` |
| zone-fill | ink @ 0.02 | ink @ 0.02 |
| store-fill | ink @ 0.05 | ink @ 0.06 |
| ext-fill / ext-stroke | ink @ 0.03 / 0.30 | ink @ 0.03 / 0.30 |

Typography per upstream: Geist (names), Geist Mono (technical/sublabels/
labels), Instrument Serif italic (annotations only). Fonts load site-wide from
`assets/extra.css`.

House conventions on top of the upstream system:

- **Accent budget spends on trust**: the focal treatment goes to the publish
  gate / trust-boundary crossing, and the security-dashed zone marks
  quarantine. Never spend it on decoration.
- The append-only ledger renders as a full-width store bar with one
  annotation instead of per-node dotted edges.

## Adding a new headline diagram

Add `docs/diagrams/<name>.mmd`, generate the pair, embed with:

```html
<!-- Diagram source of truth: docs/diagrams/<name>.mmd (Mermaid). -->
<div class="dd-diagram dd-light">
--8<-- "docs/manual/assets/diagrams/<name>-light.svg"
</div>
<div class="dd-diagram dd-dark">
--8<-- "docs/manual/assets/diagrams/<name>-dark.svg"
</div>
```

Snippet paths resolve from the repo root (mkdocs runs there).

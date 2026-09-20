## Why

The portal's **Tools** group offers exactly one outbound link, the generated API
reference at `/docs`. Everything else an operator needs while working in the
portal — the manual (how a vetting chain is configured, what a revocation means,
how the facade is cloned) and the source it is generated from — is reachable only
by leaving the product and searching for it. The manual is written for precisely
this reader and the portal never points at it.

## What Changes

- The portal's global navigation offers, beside the API reference, a link to the
  project documentation site and a link to the source repository.
- Both are external: they open in a new tab, carry `rel="noopener noreferrer"`,
  and are announced as opening a new tab so the affordance is in the
  accessibility tree and not only in an icon.
- No new configuration leaf. The two URLs are the project's own coordinates
  (ADR 0004 — the skillsgateway org and coordinates), the same pair
  `mkdocs.yml` already declares as `site_url` and `repo_url`. A deployment that
  wants to retarget them is not a case this change admits; see design.md.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-portal`: GW_INGEST_0007 — Portal marketplace and snapshot administration.
  Its presentation clause gains the outbound links in the portal's navigation.
  This is a presentation and navigation change only; the requirement's ID-level
  statement and its verification (SVC_GW_INGEST_0007) are unchanged, so no new
  requirement id is allocated.

## Impact

- `src/main/frontend/src/components/app-layout.tsx` — the **Tools** group.
- `src/main/frontend/src/components/app-layout.test.tsx` and
  `app-layout.stories.tsx` — the links are asserted by role and accessible name.
- `docs/manual/reference/portal.md` — the navigation table.
- No server change, no API change, no configuration change, no schema change.

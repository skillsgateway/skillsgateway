# Proposal: shell-names-and-version

## Why

Two things the owner spotted looking at the running instance
([#466](https://github.com/skillsgateway/skillsgateway/issues/466)), both about
the shell failing to describe itself honestly.

**The sidebar calls reference material "Tools".** The group holds *API
reference*, *Documentation* and *Source code*. None of them is a tool — nothing
under it does anything to the gateway. Two leave the portal entirely and the
third is the same-origin Scalar page. A nav label is a promise about what a
group does, and this one is wrong.

**The portal never says which version is running.** An operator looking at a
gateway cannot tell which build they are looking at without leaving the portal
for a shell. On a console whose whole job is answering "what is this gateway
doing and on whose authority", not knowing which build is answering is a gap.

## What Changes

- **The nav group is renamed to *Reference*.** One word, no other change: the
  three entries, their order, their icons and their outbound treatment all stay.
- **The sidebar footer states the running version**, read from the build rather
  than from anything a deployment can retype.
- **`GET /api/v1/me` gains a `version` field.** Additive within the major. No new
  endpoint and no new request: the portal already fetches `me` once per load with
  `staleTime: Infinity`, so the version arrives on a call that was already being
  made.
- Requirement GW_AUTH_0047 — The portal states the build it is running, with
  SVC_GW_AUTH_0047.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `admin-portal`: the session read the portal already makes on load also reports
  the gateway's own build version, and the shell states it.

## Impact

- **DB**: none.
- **Backend**: `MeController`'s view record gains one field, populated from
  Spring Boot's `BuildProperties` — already produced, because
  `spring-boot-maven-plugin` runs `build-info` in this build. No new package, no
  new endpoint, no new actuator exposure.
- **API**: one additive field on an existing response; `openapi.json`
  regenerated.
- **Portal**: `app-layout.tsx` — the group label, and a footer line.
- **Configuration**: no new settable leaves. The version is deliberately *not*
  configurable — a build that can be told to call itself something else answers
  the question dishonestly, which is worse than not answering it.
- **Trust boundary**: none. `me` is already the authenticated session read; the
  version is the same fact the jar's manifest carries and that any consumer of a
  release artifact already has.
- **Stop rule**: no new route, endpoint, package, estate object, role, sweep or
  configuration leaf. One field on a call already being made and one line of
  chrome. The capability map's Admin portal row absorbs it unchanged.
- **Docs** (same PR): `reference/portal.md` (the nav table's group name, and the
  footer), `reference/api/*` for the `me` field.

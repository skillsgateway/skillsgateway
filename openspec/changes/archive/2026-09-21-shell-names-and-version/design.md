## Context

See `proposal.md` — Why. Two independent observations, in one change because
they are the same sentence about the same component: the shell should describe
itself accurately.

Everything the version needs already exists. `spring-boot-maven-plugin` runs the
`build-info` goal (`pom.xml`), so `META-INF/build-info.properties` ships in the
jar and Spring Boot auto-configures a `BuildProperties` bean. The portal already
calls `GET /api/v1/me` once per load with `staleTime: Infinity`.

## Goals / Non-Goals

**Goals:**

- A nav group label that is true of what the group contains.
- An operator can read the running build off the portal, without leaving it.

**Non-Goals:**

- **Not a build-info endpoint.** `/actuator/info` is deliberately not exposed
  here; this needs one string, on a read that already happens.
- **Not configurable.** See the decision below.
- Not a commit SHA, a build timestamp, or a dependency list. One version string.

## Decisions

### The version rides on `me` rather than on a new endpoint

The portal already fetches `me` on load and caches it forever, so the version
costs nothing: no new endpoint, no new request, no new cache key, no new failure
mode. It is also the right place semantically — `me` is what the shell reads to
render the shell.

*Alternative: expose `/actuator/info`.* Rejected. It means opening an actuator
endpoint and deciding its authentication, which is real trust-boundary surface
for a string that can travel on a call already being made.

### The version is not configurable, on purpose

It is read from `BuildProperties` and nothing else. A version a deployment can
override is a version that can lie, and a console whose job is answering "what
is this gateway and on whose authority" must not have a field that answers
dishonestly. This also keeps `ConfigSurfaceBudgetTests` unmoved.

When `BuildProperties` is absent — which is every test context and any run from
an exploded build without `build-info` — the field is null rather than a
placeholder string, and the shell renders nothing rather than the word
"unknown". An absent version is a fact about the build; inventing text for it
would be the same dishonesty in a smaller form.

### *Reference*, not *Tools*

The group is one same-origin API reference page and two outbound links.
"Reference" is what all three are; "Links" describes the mechanism rather than
the content, and "Documentation" is wrong for the source repository.

The word is the whole change there — the entries, their order, their icons and
the outbound `opens in a new tab` treatment are untouched.

## Risks / Trade-offs

- **`MeView` grows a field unrelated to identity.** → It is the shell's own read,
  and the shell is what renders both. Splitting the gateway's self-description
  onto a second call to keep the record pure would cost a request to save a
  category.
- **The version is visible to any authenticated session, not just admins.** →
  It is already in the release artifact, the container tag and the jar manifest;
  treating it as privileged would be theatre.
- **A nav label is in the accessibility tree, so renaming it can break a test.**
  → Deliberately: the e2e suite queries the sidebar by role and name, so the
  rename must be made where those tests can see it.

# Evidence: shell-names-and-version

One fresh run of every gate after the last edit, against commit `b51b81d1`.

| Gate | Command | Result |
| --- | --- | --- |
| Java + UI + jar | `MAVEN_OPTS="-Xmx3g" ./mvnw clean verify` | `Tests run: 739, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` (06:14 min) |
| Storybook | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 11 passed (11)` · `Tests 57 passed (57)` |
| Real-browser e2e | `(cd src/main/frontend && pnpm e2e)` | `21 passed (1.2m)`, no port override |
| Requirements | `reqstool status local -p docs/reqstool` | `257/257 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 31 passed, 0 failed (31 items)` |
| Docs | `mkdocs build --strict` | built clean |

Both ratchets are unmoved: no configuration leaf (the version is deliberately
not settable) and no new Spring test context.

## A wrong assumption the tests caught

The change was written believing `BuildProperties` would be absent in the test
context — "this suite runs from classes, not a packaged jar" — and a test
asserted `$.version` did not exist. It failed: `Expected no value at JSON path
"$.version" but found: '0.3.0-183-SNAPSHOT'`. The `build-info` goal writes
`build-info.properties` into the classes directory, so the bean is present in
tests too.

That is the better outcome, and the tests were corrected to the real behaviour
rather than the assumed one: the endpoint test now asserts the running build is
reported, matched by shape so a release does not require a test edit, and the
absent case is constructed with an empty `ObjectProvider` — because it does not
occur naturally here, but a gateway run from an exploded build is a real way to
run this and must still report absence rather than a placeholder.

## What is asserted, beyond the happy path

- **Absent is absent.** Not `"unknown"`, not an empty string — null, and the
  footer renders nothing at all. A placeholder would read like a version
  somebody shipped.
- **No property can override it.** Asserted directly: the controller reads
  `BuildProperties` at construction and consults no environment, so a deployment
  cannot make the gateway misreport which build is answering.
- **The rename is asserted in both directions**: `Reference` is present and
  `Tools` is absent, so the old label cannot quietly come back.

## Design harness

`/impeccable` detector over `app-layout.tsx`: no findings. No `critique` — this
changes an existing surface rather than adding a page.

No story was added, deliberately: `app-layout.stories.tsx` stories `UserMenuView`
rather than `AppLayout`, because the shell needs a router and a query client.
Extracting the footer into a presentational shell purely to story it would test
a component that does not ship; both of its states are covered through the real
shell in `app-layout.test.tsx`.

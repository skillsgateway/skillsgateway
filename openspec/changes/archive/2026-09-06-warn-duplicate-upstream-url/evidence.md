# Evidence: warn-duplicate-upstream-url

Tier 1 (no trust boundary crossed). The check reads a list already public via
`GET /marketplaces` and refuses nothing — a match only adds an entry to the
response's `warnings`.

Implementation commit: `6ed4f2edf1501ba4d53adc8907384b0df73a6b2a`.

## Spec ↔ test mapping

| Requirement | Verifies | Test |
| --- | --- | --- |
| GW_INGEST_0029 — Warning on a duplicate upstream URL at registration | normalization rule (host case, trailing slash, `.git` suffix, both orders; path case preserved; scheme/port distinguish; null/blank/unparseable → null) | `CloneUrlNormalizerTests`, 4 tests |
| GW_INGEST_0029 | a normalized-duplicate URL warns and the registration still succeeds; a non-colliding URL carries no warning; multiple prior matches are all named | `DuplicateUrlWarningTests` (`SVC_GW_INGEST_0029`), 3 tests |
| GW_INGEST_0029 (frontend mirror) | the TypeScript `normalizeCloneUrl` produces the identical table | `form-rules.test.ts`, 4 tests |
| GW_INGEST_0029 (frontend surfacing) | a `warnings` entry in the registration response is shown as a toast even when the pre-submission dialog did not already catch it | `marketplaces.test.tsx::a_warning_in_the_registration_response_is_shown_even_when_the_client_missed_it` |

## A latent normalization bug, caught before it shipped

The first draft of `CloneUrlNormalizer` stripped the `.git` suffix before a
trailing slash, so `…/repo.git/` (slash *after* the suffix) kept its `.git`:
stripping the suffix first found no match (the string ends in `/`, not `t`),
and the slash-strip that followed only removed the trailing `/`, leaving
`…/repo.git`. `CloneUrlNormalizerTests` caught this immediately:

```
[ERROR] CloneUrlNormalizerTests.host_case_a_trailing_slash_and_a_git_suffix_all_normalize_the_same:22
expected: "https://github.com/acme/marketplace"
 but was: "https://github.com/acme/marketplace.git"
```

Fixed by stripping a trailing slash both before and after the `.git` removal.
The portal's own `normalizeCloneUrl` (from PR #227) had the exact same bug for
the exact same reason and is fixed the same way, so the client and server
rules stay identical — see `form-rules.test.ts`.

## Gates — one fresh run after the last edit

```
$ ./mvnw clean verify
[INFO] Spotless.Java is keeping 277 files clean - 0 needs changes to be clean
[INFO] You have 0 Checkstyle violations.
[INFO] Tests run: 503, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  05:26 min
```

Ran clean at commit `6ed4f2e` (the last code edit — the spotless-formatting
commit); no rerun needed, this run was green the first time.

```
$ (cd src/main/frontend && pnpm test:stories)
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

```
$ (cd src/main/frontend && pnpm e2e)
  13 passed (45.0s)
```

```
$ reqstool status local -p docs/reqstool
  GW_INGEST_0029             skills-gateway
156/156 complete · 0 incomplete · PASS
```

```
$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)
```

```
$ mkdocs build --strict
INFO    -  Documentation built in 1.52 seconds
```

No flake reruns were needed on this pass: the known `AuditExportTests`
`ConcurrentModificationException` and the Storybook import wedge did not
occur.

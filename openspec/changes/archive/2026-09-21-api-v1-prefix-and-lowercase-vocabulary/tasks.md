# Tasks: api-v1-prefix-and-lowercase-vocabulary

## 1. The version segment

- [x] 1.1 `/api/v1` on every controller mapping, and `/api/v1/me`.
- [x] 1.2 `/status/v1/snapshots` on `HeldContentController`.
- [x] 1.3 `MachineApiRegistry`'s route inventory carries the prefix.
- [x] 1.4 Security matchers deliberately left at `/api/**` and `/status/**` — see
      `design.md — Decision 1`.
- [x] 1.5 Portal API calls, e2e specs, and the regenerated types.
- [x] 1.6 44 documentation pages, without rewriting documentation *file* paths —
      the first sweep turned `reference/api/tokens.md` into `api/v1/tokens.md` and
      was reverted.

## 2. One lowercase vocabulary

- [x] 2.1 `@JsonValue` on the existing `stored()` of `VerdictState`,
      `VettingChain.Outcome`, `Severity`, `WaiverScope`, `RevetVerdict.Classification`.
- [x] 2.2 `wire()` on `ChainSource`, `LicenseEvaluation`, `BulkChainResult.Status`,
      `RevocationService.ServeAfter`, `RevetMode`, `FourEyesMode`.
- [x] 2.3 `VettingService` emits `stored()` rather than `name()` — the code was wrong,
      not only the documentation.
- [x] 2.4 Seven `allowableValues` arrays and the runtime `choices` list.
- [x] 2.5 Frontend literals across 9 files, and `audit-status.ts`'s normalisation
      direction, which the blanket sweep had broken.

## 3. The expired migration aids

- [x] 3.1 Delete `RemovedProperties`, `RemovedPropertyGuard` and their tests.
- [x] 3.2 Drop the ordering dependency from `RoleBootstrapGuard` and its test.
- [x] 3.3 Retire `GW_AUTH_0027` and `SVC_GW_AUTH_0027`; correct the `SecurityConfig`
      comment that cited it to `GW_AUTH_0025`.
- [x] 3.4 Replace the compatibility page's live-consequences paragraph: the refusals
      are gone, the policy behind them stands.

## 4. Contract and gates

- [x] 4.1 `openapi.json` regenerated; no unversioned path and no uppercase enum value
      remains in the published document.
- [x] 4.2 `./mvnw clean verify` — 693 Java, 104 UI.
- [ ] 4.3 Story tests, e2e, reqstool, `openspec validate`, `mkdocs build --strict`.
- [ ] 4.4 `evidence.md` from a final fresh run.
- [ ] 4.5 Tracker updated; change archived as the final commit.

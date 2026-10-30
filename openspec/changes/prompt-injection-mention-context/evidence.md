# Evidence: prompt-injection-mention-context

Source state: `37711915` (tree `2bd6741f`), on `main` at `daf92367`. Every gate result
below comes from one fresh run after the last code edit, on 2026-10-30.

Tier 2 (a vetting rule set, which is a trust control). Spec approval: the owner
asked for the false positives to be fixed in this session. Independent
verification was not performed. Both are declared downgrades.

## Spec → tests

| Scenario | Test | Seen failing first |
|---|---|---|
| SVC_GW_VETTING_0072: upstream lines naming a marker as vocabulary, advice, emoji or a traversal string raise nothing | `PromptInjectionMentionContextTests.vocabularyAdviceEmojiAndTraversalStringsRaiseNothing` | yes: `Do not tell the user they need …` raised concealment-instruction, high |
| SVC_GW_VETTING_0072: a quoted or prohibited marker is reported once, at medium | `PromptInjectionMentionContextTests.aQuotedOrProhibitedMarkerIsReportedOnceAtMedium` | yes: `expected WARN but was FAIL` |
| SVC_GW_VETTING_0073: each payload still raises one high finding of its rule | `PromptInjectionMentionContextTests.thePayloadsStillFail` | no: it is the recall baseline and passed before the change. M2, M4, M5, M6 and M11 fail it |
| SVC_GW_VETTING_0073: a payload behind a prohibition is still reported, at medium | `PromptInjectionMentionContextTests.aPayloadBehindAProhibitionIsStillReported` | yes: `expected MEDIUM` |

`PromptInjectionPrecisionTests` (GW_VETTING_0040 — Prompt-injection markers do
not fire on formatting instructions or command names) passes unchanged.

## Real marketplaces

`VettingPrecisionMeasurement` over shallow clones taken on 2026-10-30. "Before"
is `main` at `daf92367`; "after" is this change.

| Marketplace | prompt-injection before | after |
|---|---|---|
| anthropics/skills @ ca45fc4 | fail · 5 high | warn · 0 high, 2 medium |
| anthropics/claude-plugins-official | fail · 18 high | fail · 9 high |
| cloudflare/skills @ c668cb2 | pass · 0 | pass · 0 |
| reqstool/reqstool-ai | pass · 0 | pass · 0 |
| vercel-labs/agent-skills | pass · 0 | pass · 0 |

These are the 9 highs that remain in claude-plugins-official:
- 5 credential-path mentions in a security-review policy under `.github/`.
- `.npmrc` in prose.
- "without informing the user", used as a bug description.
- 2 `curl … | bash` installer lines in READMEs. These are correct matches.

The design names the remaining false positives as known misses.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar, UI gate | `./mvnw clean verify` | `Tests run: 1179, Failures: 0, Errors: 0, Skipped: 9` · vitest `Tests 234 passed (234)` · `BUILD SUCCESS` (8:10) |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)`. That is the second run, after clearing `node_modules/.cache/storybook` and `.vite`. The first hit the known harness flake: `Failed to fetch dynamically imported module …marketplace-sections.stories.tsx` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.7m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | 368 requirements · in code 348/348 verified · configuration 20/20 verified · tests 1197 passed, 0 failed · 0 SVCs missing tests · exit 0 |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.84 seconds` |
| Mutation | `openspec/changes/prompt-injection-mention-context/mutants.sh` | `13 mutants run, all killed` |
| Supply chain | no dependency added or changed | — |

```
M1-never-a-mention killed
M2-always-a-mention killed
M3-prohibition-ignored killed
M4-quote-without-example killed
M5-mention-outranks-plain killed
M6-clause-unbounded killed
M7-eg-is-a-break killed
M8-tell-them-conceals killed
M9-condition-conceals killed
M10-emoji-joiner-hidden killed
M11-every-joiner-allowed killed
M12-traversal-is-a-credential killed
M13-disclosure-any-word killed
13 mutants run, all killed
```

## Known limits

- A prohibition in front of a payload is cheap to add. The payload then warns
  instead of blocking, but it stays in the report.
- Patterns remain patterns: a paraphrase is not detected. The semantic answer
  is an external vetter.

# Evidence: portal-shows-invisible-characters

Source state: `0f0a2e01` (tree `643f76c3`), on `main` at `b1155dfa`. Every gate result
below comes from one fresh run after the last code edit (the review fixes), on
2026-10-30. The first run, at `017a0fa5`, was green as well.

Portal-only display change. Spec approval: the owner asked for the fix to be
implemented. Independent verification was not performed.

## Spec → tests

| Scenario | Test | Seen failing first |
|---|---|---|
| SVC_GW_APPROVAL_0040 | `lib/visible-controls.test.ts` (5 tests: every class becomes a named token; a reordered name reads in stored order; text without a control, emoji sequences included, is unchanged; a lone zero-width joiner is shown; an emoji joiner and an override are told apart) | yes: the module did not exist |
| SVC_GW_APPROVAL_0041 | `components/visible-text.test.tsx`: `a_plugin_name_description_and_skill_name_show_their_controls_as_tokens`, `a_file_name_and_a_finding_location_show_their_controls_as_tokens`, `the_source_view_names_its_file_with_the_token`, `an_external_plugin_name_and_clone_url_show_their_controls_as_tokens` | yes: names rendered the raw override, no token (the last two added in review, each seen failing before the fix) |
| SVC_GW_APPROVAL_0042 | `components/source-view-controls.test.tsx`: `a_line_with_a_control_shows_tokens_and_a_written_marker_without_a_finding`, `a_line_with_a_control_and_a_finding_is_described_by_both` | yes: no token, no marker |
| SVC_GW_APPROVAL_0043 | `components/visible-text.test.tsx`: `selecting_a_file_whose_name_holds_a_control_passes_the_path_as_written` | yes: no button carried the token in its name |

The existing SourceView, file tree, inventory, explorer and Markdown tests pass
unchanged. Stories `Snapshots/Invisible characters` (`SourceLines`,
`PluginName`) run with axe as error.

## Gates

```
$ ./mvnw clean verify
[INFO]  Test Files  34 passed (34)
[INFO]       Tests  246 passed (246)
[INFO] Tests run: 1179, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  08:10 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  22 passed (22)
      Tests  113 passed (113)

$ (cd src/main/frontend && pnpm e2e)
  27 passed (2.2m)

$ reqstool status local -p docs/reqstool        # exit 0
GW_APPROVAL_0037 │ 1 │ 10 10 - - -
GW_APPROVAL_0038 │ 1 │  2  2 - - -
Passed tests 1209 │ Failed tests 0 │ Skipped tests 0 ║ SVCs missing tests 0 │ SVCs missing MVRs 0

$ openspec validate --all --strict
Totals: 29 passed, 0 failed (29 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.73 seconds
```

The first `pnpm test:stories` attempt stopped on a harness timeout (an iframe in
`edit-marketplace-url.stories.tsx` did not become ready, no assertion failed);
after clearing the Storybook and Vite caches the run above passed.

## Screenshots

Before (`main`) and after, from the `Snapshots/Invisible characters` stories at
1280 and 390 px in light and dark, captured locally and not uploaded.

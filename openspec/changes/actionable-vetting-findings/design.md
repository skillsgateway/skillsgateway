# Design: actionable-vetting-findings

## Context

- Vetters return `Finding(id, severity, location, message)`. A location is
  normally `path:line`. `ContentRules.apply` deduplicates per rule and line.
- `QuarantineSnapshot` already indexes the pinned tree into `(path, blob)`
  entries, and caches by blob id (GW_VETTING_0030), so the blob of any path is
  known without extra reads.
- A waiver is a rule plus a scope: `SNAPSHOT` (the commit SHA) or `PATH` (a
  prefix). A snapshot-scoped waiver on a rule already covers every finding of
  that rule in the snapshot. The portal labelled that scope "This snapshot
  only", which hid how wide it is.
- The effective outcome is derived on read by `WaiverEvaluation` from the
  recorded run and the active waivers. `uncovered` is the reviewer's worklist,
  and the refusal (`VettingBlockedException`) and the override capture render
  it.

## Goals / Non-Goals

**Goals:**

- The trial's 66 false positives become none, and the payloads the rules exist
  for are still caught.
- Identical content is judged once.
- One waiver can accept one group, and nothing but that group.
- The gate names locations.
- Unscanned files are one visible fact, not 51 rows.

**Non-Goals:**

- Exempting `docs/` or any other path. This was decided on the issue.
- A free-form bulk waive.
- A semantic (LLM) review.
- Collapsing near-identical content. Vendored copies that differ by one word
  are different blobs, and the gateway cannot prove they are the same.
- Changing the recorded run: it keeps one row per location.

## Decisions

1. **The group key is (rule, severity, message, blob, line), per verdict.**
   The blob is the only equality the gateway can prove. The line is in the key
   because two lines of one blob are two different texts. A finding with no
   blob, such as an error verdict or an aggregated entry, never collapses. It
   carries an identity key of its own.
2. **The gateway stamps the blob, after the vetter answers.**
   `QuarantineSnapshot.identify(Verdict)` replaces whatever `content` a finding
   carries with the blob at its path in the pinned tree, or null. The stamp
   runs before the stop-after-fail test, so a group waiver is honoured there
   too. External vetters' wire findings have no `content` field at all.
3. **Groups are derived on read, never stored.** `VerdictView.groups` is
   computed from the findings in the constructor. `vetting_findings.content_id`
   is the only new column.
4. **A group waiver is a snapshot waiver plus `content` and `line`.** This is
   not a new scope kind. The conjunction in `Waiver.covers` only narrows, so an
   existing waiver without the qualifier behaves exactly as before. The
   qualifier requires `SNAPSHOT`. With a path scope it would outlive the commit
   it was judged on, and "one group" is a statement about this snapshot. This
   is enforced in the record constructor, in `WaiverService`, and by a table
   `CHECK`. `line` is matched exactly: null matches only a finding with no
   line.
5. **`uncovered` becomes one entry per group.** `location` stays as the first
   location, so the field keeps its meaning for a single-location group.
   `locations`, `content` and `line` are added. The refusal message and the
   override capture spell out ten locations and count the rest, which keeps
   the ledger line bounded against a pathological upstream.
6. **Precision without an allowlist.** The concealment rule loses `show` and
   `log`, which are display verbs, and its gaps stop at a clause break
   (`. `, `; `, `: `, `! `, `? `). Three explicit forms restore coverage for
   hiding an action. The dropped recall is "do not show the user X" as a
   concealment of X. That is the commonest benign use of the phrase in skill
   prose, and the triage vetter says it is triage. `credential-path-reference`
   is unchanged. Its one remaining hit on the trial repository, `id_rsa` in a
   regex literal, is a real reference to a credential file name, and it is now
   one finding.
7. **One informational entry per reason.** The reasons are over the limit, and
   binary or not UTF-8. Each entry names the first 20 paths and counts the
   rest. The summary gains "N file(s) not scanned (X over the size limit, Y
   binary)". The verdict row shows the summary whenever every finding is
   informational, which is how a pass that skipped files says so.
8. **Portal.**
   - Rows are groups.
   - The waive action is "Waive finding" (accessible name
     "Waive finding <rule> at <path:line>") or "Waive all N locations"
     ("Waive all N locations of <rule>").
   - The form's scope options are: the group (the default); every `<rule>`
     finding in this snapshot; and, only for one location, the path.
   - The blocking list is a named list ("Blocking findings") of
     `rule at a, b, c and N more`.

## Risks / Trade-offs

- **Recall loss on "show".** This is documented in the rule comment and in the
  concept page. A paraphrase walks past these rules anyway, which is why the
  vetter is labelled triage.
- **`uncovered` has one entry per group.** A consumer that counted findings now
  counts groups. The webhook `uncoveredFindings` count follows. Pre-1.0, and
  stated in the PR.
- **Grouping does not help near-identical copies.** On the trial repository,
  collapsing alone takes 66 findings to 51 groups, because most "copies" there
  are per-harness variants. Precision is what takes them to 1. Both numbers
  are in the PR.

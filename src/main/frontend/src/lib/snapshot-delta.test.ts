import { expect, test } from "vitest";
import { countDiffLines, snapshotDelta } from "./snapshot-delta";

const HEADER = "diff --git a/x b/x\nindex 111..222 100644\n--- a/x\n+++ b/x\n";

test("lines_are_counted_inside_hunks_and_the_file_headers_are_not", () => {
  const diff = `${HEADER}@@ -1,2 +1,3 @@\n context\n-old\n+new\n+added\n`;

  expect(countDiffLines(diff)).toEqual({ added: 2, removed: 1 });
});

/**
 * The case the naive rule gets wrong, and the one every SKILL.md exercises: a YAML frontmatter
 * fence is `---`, so deleting or adding one produces `----` or `+---` inside a hunk. A prefix rule
 * that skips anything starting `---` or `+++` silently drops those lines from the count.
 */
test("a_deleted_or_added_frontmatter_fence_is_a_real_line_not_a_header", () => {
  const diff = `${HEADER}@@ -1,3 +1,2 @@\n----\n name: hello\n+---\n+++ not a header either\n`;

  expect(countDiffLines(diff)).toEqual({ added: 2, removed: 1 });
});

test("the_no_newline_marker_is_not_a_line_of_content", () => {
  const diff = `${HEADER}@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+b\n`;

  expect(countDiffLines(diff)).toEqual({ added: 1, removed: 1 });
});

test("several_hunks_are_all_counted", () => {
  const diff = `${HEADER}@@ -1 +1 @@\n-a\n+b\n@@ -10 +10,2 @@\n c\n+d\n`;

  expect(countDiffLines(diff)).toEqual({ added: 2, removed: 1 });
});

test("the_delta_sums_files_and_lines_against_the_served_commit", () => {
  const delta = snapshotDelta(
    {
      baselineSha: "65f64622",
      truncated: false,
      entries: [
        { path: "a.md", type: "modified", binary: false, truncated: false, diff: `${HEADER}@@ -1 +1,2 @@\n-a\n+b\n+c\n` },
        { path: "b.md", type: "added", binary: false, truncated: false, diff: `${HEADER}@@ -0,0 +1 @@\n+new\n` },
      ],
    },
    undefined,
  );

  expect(delta).toMatchObject({ baseline: "65f64622", files: 2, added: 3, removed: 1, cut: false, binary: 0 });
});

test("a_binary_entry_counts_as_a_file_but_contributes_no_lines_and_is_counted_as_such", () => {
  const delta = snapshotDelta(
    {
      baselineSha: "65f64622",
      truncated: false,
      entries: [{ path: "logo.png", type: "added", binary: true, truncated: false }],
    },
    undefined,
  );

  expect(delta).toMatchObject({ files: 1, added: 0, removed: 0, binary: 1 });
});

/** A confident total over a cut set is the one way this line could mislead. */
test("a_cut_diff_or_a_truncated_entry_marks_the_whole_delta_as_counted_over_a_cut_set", () => {
  const listingCut = snapshotDelta({ baselineSha: "x", truncated: true, entries: [] }, undefined);
  const entryCut = snapshotDelta(
    {
      baselineSha: "x",
      truncated: false,
      entries: [{ path: "a", type: "modified", binary: false, truncated: true, diff: `${HEADER}@@ -1 +1 @@\n-a\n+b\n` }],
    },
    undefined,
  );

  expect(listingCut.cut).toBe(true);
  expect(entryCut.cut).toBe(true);
});

test("with_nothing_served_there_is_no_baseline_and_the_delta_says_so", () => {
  const delta = snapshotDelta({ baselineSha: undefined, truncated: false, entries: [] }, undefined);

  expect(delta.baseline).toBeNull();
});

test("skill_counts_are_included_when_both_reads_compare_against_the_same_commit", () => {
  const delta = snapshotDelta(
    { baselineSha: "65f64622", truncated: false, entries: [] },
    { baselineSha: "65f64622", summary: { added: 1, changed: 1, moved: 0, removed: 0, unchanged: 3 } },
  );

  expect(delta.skills).toEqual({ added: 1, changed: 1, moved: 0, removed: 0 });
});

/**
 * The file diff is against what is served; the content diff is against the last approved snapshot.
 * Those agree almost always and disagree after a withdrawal — and a line that silently added counts
 * taken against two different commits would be false in exactly the case it is read most carefully.
 */
test("skill_counts_are_withheld_when_the_two_reads_compare_against_different_commits", () => {
  const delta = snapshotDelta(
    { baselineSha: undefined, truncated: false, entries: [] },
    { baselineSha: "65f64622", summary: { added: 1, changed: 0, moved: 0, removed: 0, unchanged: 0 } },
  );

  expect(delta.skills).toBeNull();
});

test("an_empty_diff_is_zero_everywhere_rather_than_undefined", () => {
  const delta = snapshotDelta({ baselineSha: "x", truncated: false, entries: [] }, undefined);

  expect(delta).toMatchObject({ files: 0, added: 0, removed: 0, binary: 0, cut: false });
});

/**
 * The gateway's own counts win over the page: a first page of 500 entries from a diff of 603 is
 * reported as 603 files and every line of all of them, and nothing is marked as a lower bound.
 *
 * @SVCs SVC_GW_APPROVAL_0026
 */
test("the_gateways_totals_over_the_whole_diff_are_the_delta_not_the_page", () => {
  const delta = snapshotDelta(
    {
      baselineSha: "x",
      truncated: true,
      total: 603,
      summary: { binary: 1, linesAdded: 601, linesRemoved: 1 },
      entries: [{ path: "a", type: "modified", binary: false, truncated: true, diff: `${HEADER}@@ -1 +1 @@\n-a\n+b\n` }],
    },
    undefined,
  );

  expect(delta).toMatchObject({ files: 603, added: 601, removed: 1, binary: 1, cut: false });
});

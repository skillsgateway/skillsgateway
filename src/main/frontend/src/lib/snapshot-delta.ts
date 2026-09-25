/**
 * The one-line answer to "how big is this review, and what is arriving?" — assembled from two reads
 * the portal already makes, so the snapshot card can say it before anything is expanded.
 *
 * `GET /snapshots/{id}/diff` gives the changed paths and, in `total` and `summary`, the gateway's
 * own counts over the whole diff; a response without them is counted here from the page's text. `GET /snapshots/{id}/content-diff` already carries per-status skill counts in its
 * `summary`. Nothing here is asked of the server that it does not already answer.
 *
 * @Requirements GW_APPROVAL_0018, GW_APPROVAL_0026
 */

type DiffEntry = {
  path?: string;
  type?: string;
  binary?: boolean;
  truncated?: boolean;
  diff?: string;
};

type FileDiff = {
  baselineSha?: string;
  truncated?: boolean;
  entries?: readonly DiffEntry[];
  /** Changed paths over the whole diff, not the page. */
  total?: number;
  summary?: { binary?: number; linesAdded?: number; linesRemoved?: number };
};

type ContentDiff = {
  baselineSha?: string;
  summary?: { added?: number; changed?: number; moved?: number; removed?: number; unchanged?: number };
};

export type SkillCounts = { added: number; changed: number; moved: number; removed: number };

export type SnapshotDelta = {
  /** The served commit the counts are taken against; null when nothing is served. */
  baseline: string | null;
  /** Paths that differ from what is served. */
  files: number;
  added: number;
  removed: number;
  /** Changed paths that carry no text diff, so contribute a file but no lines. */
  binary: number;
  /**
   * Skill-level change, or null when it cannot honestly be combined with the file counts — see
   * {@link snapshotDelta}.
   */
  skills: SkillCounts | null;
  /** True when any count is taken over a set the gateway cut, so none of them is a total. */
  cut: boolean;
};

/**
 * Lines added and removed in one unified diff.
 *
 * Only lines **inside a hunk** are counted, and inside a hunk the first character is the whole
 * story. The obvious rule — count lines starting `+` but not `+++`, `-` but not `---` — is wrong for
 * the files this product exists to review: a YAML frontmatter fence is `---`, so deleting one reads
 * `----` and adding one reads `+---`, and that rule silently drops both. The `---`/`+++` file
 * headers only ever appear before the first `@@`, so position is what tells them apart.
 */
export function countDiffLines(diff: string): { added: number; removed: number } {
  let added = 0;
  let removed = 0;
  let inHunk = false;
  for (const line of diff.split("\n")) {
    if (line.startsWith("@@")) {
      inHunk = true;
      continue;
    }
    // Header lines before the first hunk, and the next file's header in a multi-file diff.
    if (line.startsWith("diff --git ")) {
      inHunk = false;
      continue;
    }
    if (!inHunk) continue;
    if (line.startsWith("+")) added += 1;
    else if (line.startsWith("-")) removed += 1;
    // " " is context and "\" is the no-newline marker: neither is a changed line.
  }
  return { added, removed };
}

/**
 * The delta for one snapshot.
 *
 * The two reads compare against different commits on purpose: the file diff against what is
 * **served**, the content diff against the **last approved** snapshot. Almost always those are the
 * same commit. After a withdrawal they are not — and a line that summed counts taken against two
 * different commits would be false in exactly the case it is read most carefully. So skill counts
 * are included only when both reads name the same baseline, and withheld otherwise rather than
 * mixed.
 */
export function snapshotDelta(diff: FileDiff, content: ContentDiff | undefined): SnapshotDelta {
  const entries = diff.entries ?? [];
  let files = entries.length;
  let added = 0;
  let removed = 0;
  let binary = 0;
  let cut = diff.truncated === true;
  if (diff.summary && diff.total !== undefined) {
    // The gateway counted the whole diff, so nothing here is taken over a cut set.
    files = diff.total;
    added = diff.summary.linesAdded ?? 0;
    removed = diff.summary.linesRemoved ?? 0;
    binary = diff.summary.binary ?? 0;
    cut = false;
  } else {
    for (const entry of entries) {
      if (entry.truncated) cut = true;
      if (entry.binary || !entry.diff) {
        binary += entry.binary ? 1 : 0;
        continue;
      }
      const lines = countDiffLines(entry.diff);
      added += lines.added;
      removed += lines.removed;
    }
  }

  const baseline = diff.baselineSha ?? null;
  const sameBaseline = content?.summary !== undefined && (content.baselineSha ?? null) === baseline;
  const skills = sameBaseline
    ? {
        added: content.summary?.added ?? 0,
        changed: content.summary?.changed ?? 0,
        moved: content.summary?.moved ?? 0,
        removed: content.summary?.removed ?? 0,
      }
    : null;

  return { baseline, files, added, removed, binary, skills, cut };
}

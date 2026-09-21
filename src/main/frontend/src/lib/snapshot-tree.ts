/**
 * Turning a snapshot's flat path listing into the tree a reviewer reads.
 *
 * `GET /api/v1/snapshots/{id}/files` answers with full paths and nothing else — no
 * directories, no nesting. The nesting is derived here rather than asked of the server: the
 * listing is capped at 2000 short strings the browser has already received, so a second
 * representation on the wire would buy nothing (see the change's design.md).
 *
 * Paths the snapshot *removes* relative to the served commit are, by definition, absent from
 * its own listing. They are merged in from the diff and marked, because a surface built for
 * reading a change that silently omitted the deletions would be the wrong surface.
 *
 * @Requirements GW_INGEST_0032
 */

/** How a path stands against the marketplace's currently served commit. */
export type ChangeStatus = "added" | "modified" | "removed";

export type TreeFile = {
  kind: "file";
  /** Last path segment. */
  name: string;
  /** Full path within the snapshot, as the API addresses it. */
  path: string;
  /** Blob size in bytes; absent for a removed path, which has no blob in this snapshot. */
  size?: number;
  /** Absent when the path is identical to what is served. */
  status?: ChangeStatus;
};

export type TreeDirectory = {
  kind: "directory";
  name: string;
  path: string;
  children: TreeNode[];
};

export type TreeNode = TreeFile | TreeDirectory;

type FlatEntry = { path?: string; size?: number };

/** Directories before files, then by name — the ordering every file browser uses. */
function compareNodes(a: TreeNode, b: TreeNode): number {
  if (a.kind !== b.kind) return a.kind === "directory" ? -1 : 1;
  return a.name.localeCompare(b.name);
}

function sortTree(nodes: TreeNode[]): TreeNode[] {
  nodes.sort(compareNodes);
  for (const node of nodes) {
    if (node.kind === "directory") sortTree(node.children);
  }
  return nodes;
}

/**
 * Assemble `entries`, annotated with `changes`, into a nested tree.
 *
 * `changes` maps a path to how it stands against the served commit. Paths it marks `removed`
 * are not in the snapshot's own listing — that is what removed means — so they are added to
 * the tree from here; a review surface that hid the deletions would be the wrong surface.
 *
 * A path is ignored when it is empty or when a segment of it is already held by a file: the
 * listing comes from a git tree, where that cannot happen, and inventing a directory over a
 * blob would misrepresent the commit rather than fail visibly.
 */
export function buildSnapshotTree(
  entries: readonly FlatEntry[],
  changes: ReadonlyMap<string, ChangeStatus> = new Map(),
): TreeNode[] {
  const roots: TreeNode[] = [];
  const directories = new Map<string, TreeDirectory>();

  const place = (path: string, size: number | undefined) => {
    const segments = path.split("/").filter((segment) => segment.length > 0);
    if (segments.length === 0) return;

    let siblings = roots;
    let prefix = "";
    for (const segment of segments.slice(0, -1)) {
      prefix = prefix === "" ? segment : `${prefix}/${segment}`;
      let directory = directories.get(prefix);
      if (!directory) {
        if (siblings.some((node) => node.kind === "file" && node.name === segment)) return;
        directory = { kind: "directory", name: segment, path: prefix, children: [] };
        directories.set(prefix, directory);
        siblings.push(directory);
      }
      siblings = directory.children;
    }

    const name = segments[segments.length - 1];
    if (name === undefined) return;
    if (siblings.some((node) => node.name === name)) return;
    siblings.push({ kind: "file", name, path, size, status: changes.get(path) });
  };

  for (const entry of entries) place(entry.path ?? "", entry.size);
  for (const [path, status] of changes) {
    if (status === "removed") place(path, undefined);
  }

  return sortTree(roots);
}

export type FilterResult = {
  /** The tree narrowed to matching files and the directories leading to them. */
  nodes: TreeNode[];
  /** How many files matched. */
  matched: number;
  /** How many files were searched — the size of the set the count is honest about. */
  searched: number;
};

/**
 * Narrow the tree to files whose full path contains `query`, keeping every ancestor directory
 * of a kept file so the match stays reachable. An empty or whitespace-only query is not a
 * filter and returns the tree untouched.
 *
 * The counts are returned rather than derived by the caller because the honest sentence is
 * "N of M", and M is the size of a listing that may itself be cut — a count without its
 * denominator is the misleading half of this feature.
 */
export function filterSnapshotTree(nodes: readonly TreeNode[], query: string): FilterResult {
  const needle = query.trim().toLowerCase();
  let searched = 0;
  let matched = 0;

  const walk = (input: readonly TreeNode[]): TreeNode[] => {
    const kept: TreeNode[] = [];
    for (const node of input) {
      if (node.kind === "file") {
        searched += 1;
        if (needle === "" || node.path.toLowerCase().includes(needle)) {
          matched += 1;
          kept.push(node);
        }
        continue;
      }
      const children = walk(node.children);
      if (children.length > 0) kept.push({ ...node, children });
    }
    return kept;
  };

  return { nodes: walk(nodes), matched, searched };
}

/** Every directory path on the way to `path` — what has to be open for it to be visible. */
export function ancestorDirectories(path: string): string[] {
  const segments = path.split("/").filter((segment) => segment.length > 0);
  const ancestors: string[] = [];
  let prefix = "";
  for (const segment of segments.slice(0, -1)) {
    prefix = prefix === "" ? segment : `${prefix}/${segment}`;
    ancestors.push(prefix);
  }
  return ancestors;
}

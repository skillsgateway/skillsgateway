import { expect, test } from "vitest";
import {
  ancestorDirectories,
  buildSnapshotTree,
  filterSnapshotTree,
  type TreeDirectory,
  type TreeFile,
  type TreeNode,
} from "./snapshot-tree";

const node = (nodes: readonly TreeNode[], index: number): TreeNode => {
  const found = nodes[index];
  if (!found) throw new Error(`no node at index ${index}`);
  return found;
};

const directory = (value: TreeNode | undefined): TreeDirectory => {
  if (value?.kind !== "directory") throw new Error(`${value?.path} is not a directory`);
  return value;
};

/** Full paths of every file in the tree, depth-first, in the order they would be rendered. */
function files(nodes: readonly TreeNode[]): string[] {
  return nodes.flatMap((entry) => (entry.kind === "file" ? [entry.path] : files(entry.children)));
}

test("a_flat_listing_becomes_nested_directories", () => {
  const tree = buildSnapshotTree([
    { path: "plugins/hello/skills/hello/SKILL.md", size: 42 },
    { path: ".claude-plugin/marketplace.json", size: 7 },
  ]);

  expect(tree.map((entry) => entry.name)).toEqual([".claude-plugin", "plugins"]);
  const plugins = directory(node(tree, 1));
  expect(plugins.path).toBe("plugins");
  expect(files([plugins])).toEqual(["plugins/hello/skills/hello/SKILL.md"]);
});

test("directories_sort_before_files_then_by_name", () => {
  const tree = buildSnapshotTree([
    { path: "README.md" },
    { path: "zzz/a.md" },
    { path: "LICENSE" },
    { path: "aaa/a.md" },
  ]);

  expect(tree.map((entry) => `${entry.kind}:${entry.name}`)).toEqual([
    "directory:aaa",
    "directory:zzz",
    "file:LICENSE",
    "file:README.md",
  ]);
});

test("a_single_file_snapshot_is_a_single_root_node", () => {
  const tree = buildSnapshotTree([{ path: "SKILL.md", size: 3 }]);

  expect(tree).toHaveLength(1);
  expect(node(tree, 0)).toMatchObject({ kind: "file", name: "SKILL.md", path: "SKILL.md", size: 3 });
});

test("a_deeply_nested_path_keeps_every_level", () => {
  const tree = buildSnapshotTree([{ path: "a/b/c/d/e/f.md" }]);

  let current = node(tree, 0);
  const names: string[] = [];
  while (current.kind === "directory") {
    names.push(current.name);
    current = node(current.children, 0);
  }
  expect(names).toEqual(["a", "b", "c", "d", "e"]);
  expect(current.path).toBe("a/b/c/d/e/f.md");
});

test("a_path_that_is_a_prefix_of_another_does_not_collide", () => {
  const tree = buildSnapshotTree([
    { path: "skills/hello" },
    { path: "skills/hello-world/SKILL.md" },
  ]);

  const skills = directory(node(tree, 0));
  expect(skills.children.map((child) => `${child.kind}:${child.name}`)).toEqual([
    "directory:hello-world",
    "file:hello",
  ]);
});

test("removed_paths_are_merged_in_and_marked", () => {
  const tree = buildSnapshotTree(
    [{ path: "skills/kept.md", size: 1 }],
    new Map([
      ["skills/gone.md", "removed"],
      ["dropped/entirely.md", "removed"],
    ]),
  );

  expect(files(tree)).toEqual(["dropped/entirely.md", "skills/gone.md", "skills/kept.md"]);
  const dropped = directory(node(tree, 0));
  expect(node(dropped.children, 0)).toMatchObject({ status: "removed", size: undefined });
  const skills = directory(node(tree, 1));
  expect(skills.children.map((child) => (child as TreeFile).status)).toEqual([
    "removed",
    undefined,
  ]);
});

test("a_changed_path_the_snapshot_carries_keeps_its_blob_and_gains_its_status", () => {
  const tree = buildSnapshotTree(
    [
      { path: "a.md", size: 5 },
      { path: "b.md", size: 6 },
    ],
    new Map([["a.md", "modified"]]),
  );

  expect(node(tree, 0)).toMatchObject({ status: "modified", size: 5 });
  // An unchanged path carries no status at all, rather than a status meaning "unchanged".
  expect(node(tree, 1)).toMatchObject({ status: undefined, size: 6 });
});

test("an_empty_path_is_ignored_rather_than_becoming_a_node", () => {
  expect(buildSnapshotTree([{ path: "" }, {}])).toEqual([]);
});

test("a_filter_keeps_matches_and_the_directories_leading_to_them", () => {
  const tree = buildSnapshotTree([
    { path: "plugins/hello/SKILL.md" },
    { path: "plugins/other/README.md" },
    { path: "LICENSE" },
  ]);

  const result = filterSnapshotTree(tree, "SKILL");

  expect(files(result.nodes)).toEqual(["plugins/hello/SKILL.md"]);
  expect(result.nodes.map((entry) => entry.name)).toEqual(["plugins"]);
  expect(result).toMatchObject({ matched: 1, searched: 3 });
});

test("a_filter_matching_nothing_reports_the_set_it_searched", () => {
  const tree = buildSnapshotTree([{ path: "a/b.md" }, { path: "c.md" }]);

  const result = filterSnapshotTree(tree, "nothing-here");

  expect(result.nodes).toEqual([]);
  expect(result).toMatchObject({ matched: 0, searched: 2 });
});

test("a_filter_matches_on_the_full_path_not_only_the_name", () => {
  const tree = buildSnapshotTree([{ path: "plugins/hello/README.md" }, { path: "README.md" }]);

  expect(files(filterSnapshotTree(tree, "hello/").nodes)).toEqual(["plugins/hello/README.md"]);
});

test("an_empty_or_whitespace_filter_is_not_a_filter", () => {
  const tree = buildSnapshotTree([{ path: "a/b.md" }, { path: "c.md" }]);

  for (const query of ["", "   "]) {
    const result = filterSnapshotTree(tree, query);
    expect(files(result.nodes)).toEqual(["a/b.md", "c.md"]);
    expect(result).toMatchObject({ matched: 2, searched: 2 });
  }
});

test("a_filter_is_case_insensitive", () => {
  const tree = buildSnapshotTree([{ path: "plugins/Hello/SKILL.md" }]);

  expect(filterSnapshotTree(tree, "skill.MD").matched).toBe(1);
});

test("ancestor_directories_are_every_level_above_a_path", () => {
  expect(ancestorDirectories("a/b/c/d.md")).toEqual(["a", "a/b", "a/b/c"]);
  expect(ancestorDirectories("root.md")).toEqual([]);
});

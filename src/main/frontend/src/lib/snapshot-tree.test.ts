import { expect, test } from "vitest";
import { ancestorDirectories } from "./snapshot-tree";

test("ancestor_directories_are_every_level_above_a_path", () => {
  expect(ancestorDirectories("a/b/c/d.md")).toEqual(["a", "a/b", "a/b/c"]);
  expect(ancestorDirectories("root.md")).toEqual([]);
});

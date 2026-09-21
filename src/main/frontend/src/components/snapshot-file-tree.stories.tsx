import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, userEvent, within } from "storybook/test";
import { buildSnapshotTree, filterSnapshotTree, type ChangeStatus } from "@/lib/snapshot-tree";
import { SnapshotFileTree } from "./snapshot-file-tree";

const ENTRIES = [
  { path: ".claude-plugin/marketplace.json", size: 180 },
  { path: "plugins/hello/skills/hello/SKILL.md", size: 120 },
  { path: "plugins/hello/skills/hello/reference.md", size: 4096 },
  { path: "plugins/other/README.md", size: 64 },
  { path: "LICENSE", size: 1024 },
];

const CHANGES = new Map<string, ChangeStatus>([
  ["docs/OLD.md", "removed"],
  ["plugins/hello/skills/hello/SKILL.md", "modified"],
  ["plugins/other/README.md", "added"],
]);

/** The tree as the page drives it: expansion is state, selection comes from the address. */
function Harness({
  open = ["plugins"],
  query = "",
  selected = null,
}: {
  open?: string[];
  query?: string;
  selected?: string | null;
}) {
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set(open));
  const [path, setPath] = useState<string | null>(selected);
  const tree = filterSnapshotTree(buildSnapshotTree(ENTRIES, CHANGES), query);
  return (
    <div className="w-80 rounded-lg border p-3">
      <SnapshotFileTree
        nodes={tree.nodes}
        selectedPath={path}
        expanded={expanded}
        onToggle={(directory) =>
          setExpanded((current) => {
            const next = new Set(current);
            if (!next.delete(directory)) next.add(directory);
            return next;
          })
        }
        onSelect={setPath}
      />
    </div>
  );
}

/**
 * The reviewer's map of a snapshot: directories before files, deletions marked, and nothing
 * opened that the reviewer did not open.
 *
 * @Requirements GW_INGEST_0032
 */
const meta = {
  title: "Snapshots/FileTree",
  component: Harness,
} satisfies Meta<typeof Harness>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Everything closed: the whole snapshot as its top-level directories and a file. */
export const Collapsed: Story = {
  args: { open: [] },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("button", { name: "plugins" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    // Nothing below a closed directory is on screen — that is the whole point of the tree.
    await expect(canvas.queryByRole("button", { name: /SKILL\.md/ })).not.toBeInTheDocument();
  },
};

/** Opened down to a skill, with that skill's file selected as a deep link would leave it. */
export const OpenedToASkill: Story = {
  args: {
    open: ["plugins", "plugins/hello", "plugins/hello/skills", "plugins/hello/skills/hello"],
    selected: "plugins/hello/skills/hello/SKILL.md",
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("button", { name: /SKILL\.md/ })).toHaveAttribute(
      "aria-current",
      "true",
    );
  },
};

export const OpenedToASkillDark: Story = {
  ...OpenedToASkill,
  parameters: { theme: "dark" },
};

/** A path the snapshot drops: in the tree, marked, and not pretending to have a size. */
export const RemovedPath: Story = {
  args: { open: ["docs"] },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("button", { name: /OLD\.md/ })).toHaveTextContent("removed");
  },
};

/** What changed, marked in the row a reviewer is already scanning. */
export const ChangesAreMarked: Story = {
  args: { open: ["plugins", "plugins/hello", "plugins/hello/skills", "plugins/hello/skills/hello", "plugins/other", "docs"] },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("button", { name: /SKILL\.md/ })).toHaveTextContent("modified");
    await expect(canvas.getByRole("button", { name: /other\/README\.md/ })).toHaveTextContent(
      "added",
    );
    // An unchanged file keeps its size in that slot; it has nothing to report.
    await expect(canvas.getByRole("button", { name: /reference\.md/ })).toHaveTextContent("4 KiB");
  },
};

/** Narrowed to one match, with the directories above it kept so the match is reachable. */
export const Filtered: Story = {
  args: {
    open: ["plugins", "plugins/hello", "plugins/hello/skills", "plugins/hello/skills/hello"],
    query: "SKILL",
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("button", { name: /SKILL\.md/ })).toBeVisible();
    await expect(canvas.queryByRole("button", { name: /LICENSE/ })).not.toBeInTheDocument();
  },
};

/** A directory opens and closes from the keyboard alone, which is the contract axe checks. */
export const TogglesFromTheKeyboard: Story = {
  args: { open: [] },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const plugins = canvas.getByRole("button", { name: "plugins" });
    plugins.focus();
    await userEvent.keyboard("{Enter}");
    await expect(plugins).toHaveAttribute("aria-expanded", "true");
    await expect(canvas.getByRole("button", { name: "hello" })).toBeVisible();
  },
};

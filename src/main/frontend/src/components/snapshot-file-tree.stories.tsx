import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState, type ReactNode } from "react";
import { expect, userEvent, within } from "storybook/test";
import type { SnapshotTreeChild } from "@/api/queries";
import { SnapshotFileTree } from "./snapshot-file-tree";

const dir = (path: string, files: number, changed = 0): SnapshotTreeChild => ({
  kind: "directory",
  name: path.split("/").at(-1),
  path,
  files,
  changed,
});
const file = (path: string, size?: number, status?: SnapshotTreeChild["status"]): SnapshotTreeChild => ({
  kind: "file",
  name: path.split("/").at(-1),
  path,
  size,
  status,
});

/** One listing per directory, as `GET /snapshots/{id}/tree?dir=` answers it. */
const LISTINGS: Record<string, SnapshotTreeChild[]> = {
  "": [
    dir(".claude-plugin", 1),
    dir("docs", 0, 1),
    dir("plugins", 3, 2),
    file("LICENSE", 1024),
  ],
  ".claude-plugin": [file(".claude-plugin/marketplace.json", 180)],
  docs: [file("docs/OLD.md", undefined, "removed")],
  plugins: [dir("plugins/hello", 2, 1), dir("plugins/other", 1, 1)],
  "plugins/hello": [dir("plugins/hello/skills", 2, 1)],
  "plugins/hello/skills": [dir("plugins/hello/skills/hello", 2, 1)],
  "plugins/hello/skills/hello": [
    file("plugins/hello/skills/hello/SKILL.md", 120, "modified"),
    file("plugins/hello/skills/hello/reference.md", 4096),
  ],
  "plugins/other": [file("plugins/other/README.md", 64, "added")],
};

/** The tree as the page drives it: expansion is state, selection comes from the address. */
function Harness({
  open = ["plugins"],
  selected = null,
  matches,
}: {
  open?: string[];
  selected?: string | null;
  /** Render a flat list of search matches instead of the tree. */
  matches?: SnapshotTreeChild[];
}) {
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set(open));
  const [path, setPath] = useState<string | null>(selected);
  const props = {
    selectedPath: path,
    expanded,
    onToggle: (directory: string) =>
      setExpanded((current) => {
        const next = new Set(current);
        if (!next.delete(directory)) next.add(directory);
        return next;
      }),
    onSelect: setPath,
  };
  const level = (at: string): ReactNode => (
    <SnapshotFileTree entries={LISTINGS[at] ?? []} {...props} renderChildren={level} />
  );
  return (
    <div className="w-80 rounded-lg border p-3">
      {matches ? (
        <SnapshotFileTree entries={matches} {...props} fullPaths renderChildren={() => null} />
      ) : (
        level("")
      )}
    </div>
  );
}

/**
 * The reviewer's map of a snapshot: directories before files, deletions marked, and nothing
 * opened that the reviewer did not open.
 *
 * @Requirements GW_INGEST_0032, GW_APPROVAL_0025
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
    await expect(canvas.getByRole("button", { name: /^plugins,/ })).toHaveAttribute(
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

/** A folder says how much of it changed, so the change is found without opening every folder. */
export const FoldersCountTheirChanges: Story = {
  args: { open: [] },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("button", { name: /^plugins,/ })).toHaveAccessibleName(
      "plugins, 3 files, 2 changed",
    );
    await expect(canvas.getByRole("button", { name: /^plugins,/ })).toHaveTextContent("2 changed");
    // Nothing changed beneath it: the slot gives the size of the folder instead.
    await expect(canvas.getByRole("button", { name: /^\.claude-plugin,/ })).toHaveAccessibleName(
      ".claude-plugin, 1 file",
    );
  },
};

/** Search matches from anywhere in the snapshot, each shown by its full path. */
export const SearchMatches: Story = {
  args: {
    matches: [
      file("plugins/hello/skills/hello/SKILL.md", 120),
      file("plugins/other/skills/other/SKILL.md", 88),
    ],
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      canvas.getByRole("button", { name: /hello\/skills\/hello\/SKILL\.md/ }),
    ).toHaveTextContent("plugins/hello/skills/hello/SKILL.md");
    await expect(canvas.queryByRole("button", { name: /LICENSE/ })).not.toBeInTheDocument();
  },
};

/** A directory opens and closes from the keyboard alone, which is the contract axe checks. */
export const TogglesFromTheKeyboard: Story = {
  args: { open: [] },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const plugins = canvas.getByRole("button", { name: /^plugins,/ });
    plugins.focus();
    await userEvent.keyboard("{Enter}");
    await expect(plugins).toHaveAttribute("aria-expanded", "true");
    await expect(canvas.getByRole("button", { name: /^hello,/ })).toBeVisible();
  },
};

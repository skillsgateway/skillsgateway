import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import { snapshotContent } from "@/test/msw-handlers";
import { PluginInventory } from "./snapshot-inventory";

/**
 * One plugin of a snapshot's inventory: a collapsed count per component kind, each expanding in
 * place. The fixture has the trial's shape — one skill, four agents, three hooks.
 *
 * @Requirements GW_INGEST_0046
 */
const meta = {
  title: "Snapshots/PluginInventory",
  component: PluginInventory,
  decorators: [
    (Story) => (
      <div className="max-w-2xl p-4">
        <Story />
      </div>
    ),
  ],
  args: { plugin: snapshotContent.plugins![1]! },
} satisfies Meta<typeof PluginInventory>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Every count collapsed: the shape of the plugin at a glance. */
export const Collapsed: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("button", { name: "3 hooks" })).toHaveAttribute("aria-expanded", "false");
    await expect(canvas.queryByRole("region", { name: "hooks of review" })).toBeNull();
  },
};

/** The hooks expanded: each trigger and the command it runs. */
export const HooksExpanded: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByRole("button", { name: "3 hooks" }));
    const hooks = canvas.getByRole("region", { name: "hooks of review" });
    await expect(within(hooks).getByText("PostToolUse · Edit|Write")).toBeVisible();
  },
};

/** Every kind expanded at once. */
export const AllExpanded: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    for (const name of ["1 skill", "4 agents", "3 hooks"]) {
      await userEvent.click(canvas.getByRole("button", { name }));
    }
    await expect(canvas.getByRole("region", { name: "agents of review" })).toBeVisible();
  },
};

/** A plugin with nothing the layout recognises. */
export const Empty: Story = {
  args: { plugin: { name: "empty", source: "./empty", skills: [] } },
};

/** Hardening: a long name, a long source and a long one-line command wrap rather than overflow. */
export const LongValues: Story = {
  args: {
    plugin: {
      name: "a-plugin-name-that-goes-on-for-much-longer-than-anyone-would-reasonably-choose",
      source: "./vendor/third-party/plugins/a-plugin-name-that-goes-on-for-much-longer",
      skills: [],
      hooks: [
        {
          event: "PreToolUse",
          matcher: "Bash|Edit|Write|MultiEdit|NotebookEdit",
          type: "command",
          runs: `bash -c "${"set -eu; ".repeat(30)}exec \${CLAUDE_PLUGIN_ROOT}/scripts/check.sh"`,
          location: "vendor/third-party/plugins/a-plugin-name/hooks/hooks.json:12",
          declaredBy: "skill secure-operations",
        },
      ],
    },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByRole("button", { name: "1 hook" }));
    await expect(canvas.getByText(/declared by skill secure-operations/)).toBeVisible();
    await expect(canvasElement.scrollWidth).toBeLessThanOrEqual(canvasElement.clientWidth);
  },
};

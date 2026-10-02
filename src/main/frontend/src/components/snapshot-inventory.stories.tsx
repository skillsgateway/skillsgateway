import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import { snapshotContent } from "@/test/msw-handlers";
import { PluginInventory } from "./snapshot-inventory";

/**
 * One plugin of a snapshot's inventory: a collapsed count per component kind, each expanding in
 * place. The fixture has the trial's shape — one skill, four agents, three hooks — plus a hook module.
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
    for (const name of ["1 skill", "4 agents", "3 hooks", "1 hook module"]) {
      await userEvent.click(canvas.getByRole("button", { name }));
    }
    await expect(canvas.getByRole("region", { name: "agents of review" })).toBeVisible();
  },
};

/** The hook module expanded: its events, the engine interfaces it reaches, and what it imports. */
export const HookModulesExpanded: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByRole("button", { name: "1 hook module" }));
    const modules = canvas.getByRole("region", { name: "hook modules of review" });
    await expect(within(modules).getByText("tool.call")).toBeVisible();
    await expect(within(modules).getByText("$.process")).toBeVisible();
  },
};

/** A mod whose imports could not all be read: each one is named, so nothing passes unseen. */
export const HookModuleNotScanned: Story = {
  args: {
    plugin: {
      name: "mod",
      source: "./mod",
      skills: [],
      hookModules: [
        {
          path: "mod/hooks/register.ts",
          location: "mod/hooks/hooks.json:1",
          events: [],
          uses: [{ name: "model", location: "mod/hooks/register.ts:9" }],
          files: ["mod/hooks/register.ts"],
          unscanned: [
            {
              path: "mod/hooks/register.ts:2",
              message: "imports the package 'lodash', which is outside the plugin, so no rule read it",
            },
            { path: "mod/hooks/engine.js", message: "binary, so no rule can read it" },
          ],
        },
      ],
    },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByRole("button", { name: "1 hook module" }));
    await expect(canvas.getByText(/not scanned: mod\/hooks\/engine.js/)).toBeVisible();
    await expect(canvasElement.scrollWidth).toBeLessThanOrEqual(canvasElement.clientWidth);
  },
};

/** A language plugin: its LSP servers, each a process Claude Code starts for matching files. */
export const LspServersExpanded: Story = {
  args: {
    plugin: {
      name: "lang",
      source: "./lang",
      skills: [],
      lspServers: [
        { name: "go", path: "plugins/lang/.lsp.json:2" },
        { name: "typescript", path: "plugins/lang/.claude-plugin/plugin.json:4" },
      ],
    },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByRole("button", { name: "2 LSP servers" }));
    const servers = canvas.getByRole("region", { name: "LSP servers of lang" });
    await expect(within(servers).getByText("typescript")).toBeVisible();
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

import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test } from "vitest";
import { snapshotContent } from "@/test/msw-handlers";
import { PluginInventory } from "./snapshot-inventory";

const review = snapshotContent.plugins![1]!;

/**
 * @SVCs SVC_GW_INGEST_0046
 */
test("each_component_kind_is_a_collapsed_count_that_expands_in_place", async () => {
  const user = userEvent.setup();
  render(<PluginInventory plugin={review} />);

  // One count per kind the plugin has, none for a kind it lacks, every one collapsed.
  const counts = ["1 skill", "4 agents", "3 hooks"].map((name) => screen.getByRole("button", { name }));
  for (const count of counts) expect(count).toHaveAttribute("aria-expanded", "false");
  expect(screen.queryByRole("button", { name: /command/ })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /MCP server/ })).not.toBeInTheDocument();
  expect(screen.queryByRole("region", { name: "hooks of review" })).not.toBeInTheDocument();

  // Expanding the hooks shows each trigger and command in place; the others stay collapsed.
  await user.click(screen.getByRole("button", { name: "3 hooks" }));
  expect(screen.getByRole("button", { name: "3 hooks" })).toHaveAttribute("aria-expanded", "true");
  const hooks = screen.getByRole("region", { name: "hooks of review" });
  expect(within(hooks).getByText("SessionStart")).toBeInTheDocument();
  expect(within(hooks).getByText("PostToolUse · Edit|Write")).toBeInTheDocument();
  expect(within(hooks).getByText("Stop")).toBeInTheDocument();
  expect(within(hooks).getAllByText('"${CLAUDE_PLUGIN_ROOT}/scripts/engine" hook')).toHaveLength(3);
  expect(within(hooks).getByText("plugins/review/hooks/hooks.json:21")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "4 agents" })).toHaveAttribute("aria-expanded", "false");
  expect(screen.queryByRole("region", { name: "agents of review" })).not.toBeInTheDocument();

  // And it collapses again.
  await user.click(screen.getByRole("button", { name: "3 hooks" }));
  expect(screen.queryByRole("region", { name: "hooks of review" })).not.toBeInTheDocument();

  // A hook module is a kind of its own: its path, the events it registers and what it reaches.
  const modules = screen.getByRole("button", { name: "1 hook module" });
  expect(modules).toHaveAttribute("aria-expanded", "false");
  await user.click(modules);
  const region = screen.getByRole("region", { name: "hook modules of review" });
  expect(within(region).getByText("plugins/review/mod/register.tsx")).toBeInTheDocument();
  expect(within(region).getByText("session.start")).toBeInTheDocument();
  expect(within(region).getByText("tool.call")).toBeInTheDocument();
  expect(within(region).getByText("$.process")).toBeInTheDocument();
  expect(within(region).getByText("$.ui")).toBeInTheDocument();
  expect(within(region).getByText("declared at plugins/review/mod/hooks.json:2")).toBeInTheDocument();
});

test("a_hook_module_names_what_it_imports_and_what_could_not_be_scanned", async () => {
  const user = userEvent.setup();
  render(
    <PluginInventory
      plugin={{
        name: "mod",
        source: "./mod",
        skills: [],
        hookModules: [
          {
            path: "mod/hooks/register.ts",
            location: "mod/hooks/hooks.json:1",
            events: [],
            uses: [],
            files: ["mod/hooks/register.ts", "mod/hooks/lib.ts"],
            unscanned: [
              {
                path: "mod/hooks/register.ts:2",
                message: "imports the package 'lodash', which is outside the plugin, so no rule read it",
              },
            ],
          },
        ],
      }}
    />,
  );

  await user.click(screen.getByRole("button", { name: "1 hook module" }));
  const region = screen.getByRole("region", { name: "hook modules of mod" });
  expect(within(region).getByText("imports mod/hooks/lib.ts")).toBeInTheDocument();
  expect(within(region).getByText("no events a scan can see")).toBeInTheDocument();
  expect(
    within(region).getByText(
      "not scanned: mod/hooks/register.ts:2 — imports the package 'lodash', which is outside the plugin, so no rule read it",
    ),
  ).toBeInTheDocument();
});

test("a_plugin_with_no_components_says_so", () => {
  render(<PluginInventory plugin={{ name: "empty", source: "./empty", skills: [] }} />);
  expect(screen.getByText("no components found")).toBeInTheDocument();
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
});

/**
 * @SVCs SVC_GW_INGEST_0045
 */
test("lsp_servers_are_a_count_that_expands_to_each_server_and_its_declaration", async () => {
  const user = userEvent.setup();
  render(
    <PluginInventory
      plugin={{
        name: "lang",
        source: "./lang",
        skills: [],
        lspServers: [
          { name: "go", path: "plugins/lang/.lsp.json:2" },
          { name: "ts", path: "plugins/lang/.claude-plugin/plugin.json:4" },
        ],
      }}
    />,
  );

  await user.click(screen.getByRole("button", { name: "2 LSP servers" }));
  const servers = screen.getByRole("region", { name: "LSP servers of lang" });
  expect(within(servers).getByText("go")).toBeInTheDocument();
  expect(within(servers).getByText("plugins/lang/.claude-plugin/plugin.json:4")).toBeInTheDocument();
});

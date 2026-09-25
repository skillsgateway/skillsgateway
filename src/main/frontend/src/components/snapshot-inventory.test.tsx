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
});

test("a_plugin_with_no_components_says_so", () => {
  render(<PluginInventory plugin={{ name: "empty", source: "./empty", skills: [] }} />);
  expect(screen.getByText("no components found")).toBeInTheDocument();
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
});

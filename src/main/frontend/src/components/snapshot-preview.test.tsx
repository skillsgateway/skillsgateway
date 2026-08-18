import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test } from "vitest";
import { SnapshotPreview } from "./snapshot-preview";

function renderPreview() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <SnapshotPreview snapshotId={1} />
    </QueryClientProvider>,
  );
}

test("file_tree_lists_paths_and_the_skill_md_opens_rendered_and_inert", async () => {
  renderPreview();
  const tree = await screen.findByRole("navigation", { name: "File tree of snapshot 1" });
  expect(tree).toBeInTheDocument();
  expect(screen.getByText(".claude-plugin/marketplace.json")).toBeInTheDocument();
  // SKILL.md is quick-opened by default and rendered as markdown, not raw text.
  expect(await screen.findByRole("heading", { name: "Hello skill" })).toBeInTheDocument();
  // The hostile embedded HTML is visible as text and produced no element.
  expect(screen.getByText(/<img src=x onerror=alert\(1\)>/)).toBeInTheDocument();
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
});

test("binary_and_truncated_files_are_described_rather_than_rendered", async () => {
  const user = userEvent.setup();
  renderPreview();
  await screen.findByRole("navigation", { name: "File tree of snapshot 1" });
  await user.click(screen.getByRole("button", { name: /assets\/logo\.bin/ }));
  expect(await screen.findByText(/Binary file \(4096 bytes\)/)).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: /data\/huge\.txt/ }));
  expect(await screen.findByText(/Truncated: showing the first part of 900000 bytes/)).toBeInTheDocument();
  expect(screen.getByText("first part only")).toBeInTheDocument();
});

test("diff_view_names_the_baseline_and_the_added_modified_and_removed_paths", async () => {
  const user = userEvent.setup();
  renderPreview();
  await screen.findByRole("navigation", { name: "File tree of snapshot 1" });
  await user.click(screen.getByRole("button", { name: "Diff of snapshot 1 vs served" }));
  expect(
    await screen.findByText(/1111222233334444555566667777888899990000/),
  ).toBeInTheDocument();
  expect(screen.getByText("modified")).toBeInTheDocument();
  expect(screen.getByText("added")).toBeInTheDocument();
  expect(screen.getByText("removed")).toBeInTheDocument();
  expect(screen.getByText("docs/NEW.md")).toBeInTheDocument();
  await user.click(
    screen.getByRole("button", { name: "Show diff of plugins/hello/skills/hello/SKILL.md" }),
  );
  expect(await screen.findByText("+new instruction")).toBeInTheDocument();
  expect(screen.getByText("-old instruction")).toBeInTheDocument();
});

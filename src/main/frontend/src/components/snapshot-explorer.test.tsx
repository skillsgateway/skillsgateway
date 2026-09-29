import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test } from "vitest";
import { locatedVetting } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { SnapshotExplorer } from "./snapshot-explorer";

const SKILL = "plugins/hello/skills/hello/SKILL.md";

function renderExplorer(path: string, line: number | null = null) {
  server.use(http.get("/api/v1/snapshots/:id/vetting", () => HttpResponse.json(locatedVetting)));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <SnapshotExplorer snapshotId={1} selectedPath={path} onSelect={() => {}} selectedLine={line} />
    </QueryClientProvider>,
  );
}

/** The file pane appears once the tree root has loaded. */
const pane = async () => within(await screen.findByRole("region", { name: "Selected file" }));

/**
 * @SVCs SVC_GW_APPROVAL_0029
 */
test("a_file_with_findings_opens_numbered_with_its_findings_listed_and_marked", async () => {
  const user = userEvent.setup();
  const { container } = renderExplorer(SKILL);

  const summary = await (await pane()).findByRole("region", { name: "Findings in this file" });
  expect(summary).toHaveTextContent("3 findings in this file");
  expect(summary).toHaveTextContent("no line");
  expect((await pane()).getByRole("list", { name: `Lines of ${SKILL}` })).toBeInTheDocument();
  const line5 = container.querySelector<HTMLElement>('[data-line="5"]')!;
  expect(line5).toHaveAttribute("data-severity", "high");
  expect(within(line5).getAllByRole("note")).toHaveLength(2);

  await user.click(within(summary).getByRole("button", { name: /high · html-in-markdown · line 5/ }));
  expect(document.activeElement).toBe(line5);

  // The rendered view is one control away, and moving to a line comes back to the source.
  await user.click((await pane()).getByRole("button", { name: "Rendered" }));
  expect(await (await pane()).findByRole("heading", { name: "Hello skill" })).toBeInTheDocument();
  await user.click(within(summary).getByRole("button", { name: /medium · generic-token · line 5/ }));
  expect((await pane()).getByRole("list", { name: `Lines of ${SKILL}` })).toBeInTheDocument();
});

/**
 * @SVCs SVC_GW_APPROVAL_0029
 */
test("a_finding_beyond_a_truncated_file_is_listed_and_described", async () => {
  renderExplorer("data/huge.txt");

  const summary = await (await pane()).findByRole("region", { name: "Findings in this file" });
  expect(summary).toHaveTextContent("1 finding in this file");
  expect(await (await pane()).findByText(/line 40 is beyond the part shown/i)).toBeInTheDocument();
});

/**
 * @SVCs SVC_GW_APPROVAL_0029
 */
test("a_markdown_file_without_findings_keeps_its_rendered_view", async () => {
  renderExplorer("docs/NEW.md");

  expect(await (await pane()).findByRole("heading", { name: "Hello skill" })).toBeInTheDocument();
  expect((await pane()).queryByRole("region", { name: "Findings in this file" })).not.toBeInTheDocument();
  expect((await pane()).queryByRole("list", { name: /^Lines of/ })).not.toBeInTheDocument();
});

/**
 * @SVCs SVC_GW_APPROVAL_0030
 */
test("an_addressed_line_is_focused_once_the_file_is_shown", async () => {
  const { container } = renderExplorer(SKILL, 5);

  await (await pane()).findByRole("list", { name: `Lines of ${SKILL}` });
  expect(document.activeElement).toBe(container.querySelector('[data-line="5"]'));
});

/**
 * @SVCs SVC_GW_APPROVAL_0029
 */
test("the_tree_marks_a_file_with_findings", async () => {
  renderExplorer(SKILL);

  const tree = within(await screen.findByRole("navigation", { name: /File tree of snapshot/ }));
  expect(
    await tree.findByRole("button", { name: `${SKILL}, modified, 3 findings, highest high` }),
  ).toHaveTextContent("high · 3");
});

/**
 * @SVCs SVC_GW_APPROVAL_0029
 */
test("a_vetting_read_that_fails_leaves_the_file_readable", async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  server.use(http.get("/api/v1/snapshots/:id/vetting", () => HttpResponse.json({ detail: "no" }, { status: 403 })));
  render(
    <QueryClientProvider client={queryClient}>
      <SnapshotExplorer snapshotId={1} selectedPath="docs/NEW.md" onSelect={() => {}} />
    </QueryClientProvider>,
  );

  expect(await (await pane()).findByRole("heading", { name: "Hello skill" })).toBeInTheDocument();
});

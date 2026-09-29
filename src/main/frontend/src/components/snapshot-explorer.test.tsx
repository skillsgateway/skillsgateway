import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test } from "vitest";
import type { VettingView } from "@/api/queries";
import { server } from "@/test/msw-server";
import { SnapshotExplorer } from "./snapshot-explorer";

const SKILL = "plugins/hello/skills/hello/SKILL.md";

/** A run locating findings on the fixture's files: SKILL.md line 5 twice and once without a line. */
export const locatedVetting: VettingView = {
  snapshotId: 1,
  outcome: "blocked",
  run: {
    verdicts: [
      {
        vetter: "prompt-injection",
        state: "fail",
        findings: [
          { id: "html-in-markdown", severity: "high", location: `${SKILL}:5`, message: "raw HTML with an event handler" },
          { id: "file-level", severity: "low", location: SKILL, message: "a finding about the whole file" },
        ],
      },
      {
        vetter: "secret-scan",
        state: "warn",
        findings: [{ id: "generic-token", severity: "medium", location: `${SKILL}:5`, message: "looks like a token" }],
      },
      {
        vetter: "executable-surface",
        state: "warn",
        findings: [{ id: "runtime-dependency", severity: "medium", location: "data/huge.txt:40", message: "installs" }],
      },
    ],
  },
  suppressed: [],
};

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

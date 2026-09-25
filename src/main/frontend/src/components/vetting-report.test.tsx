import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test, vi } from "vitest";
import {
  blockedVetting,
  supersededChainVetting,
  undeterminedChainVetting,
  vendoredVetting,
} from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { VettingReport } from "./vetting-report";
import type { VettingView } from "@/api/queries";

function renderReport() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <VettingReport snapshotId={1} />
    </QueryClientProvider>,
  );
}

function vettingIs(view: VettingView) {
  server.use(http.get("/api/v1/snapshots/:id/vetting", () => HttpResponse.json(view)));
}

test("evidence_from_the_chain_in_force_says_nothing_at_all", async () => {
  vettingIs(blockedVetting);
  renderReport();
  await screen.findByRole("region", { name: "Vetting of snapshot 1" });

  // A marking on every snapshot would be noise, and noise is not evidence.
  expect(screen.queryByText(/different chain than this marketplace runs now/)).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: /Re-run the vetting chain/ }),
  ).not.toBeInTheDocument();
});

test("a_superseded_chain_is_named_on_both_sides_and_says_it_does_not_block", async () => {
  vettingIs(supersededChainVetting);
  renderReport();

  expect(
    await screen.findByText(/different chain than this marketplace runs now/),
  ).toBeInTheDocument();
  // Both descriptions, or a reviewer cannot tell what the difference is.
  expect(screen.getByText("secret-scan@1,prompt-injection@1;mode=run-all")).toBeInTheDocument();
  expect(
    screen.getByText("secret-scan@1,prompt-injection@1;mode=run-all;disabled=[secret-scan]"),
  ).toBeInTheDocument();
  // The decision stays the reviewer's, and the page says so rather than implying a block.
  expect(screen.getByText(/Approval is not blocked by this/)).toBeInTheDocument();
});

test("the_marking_comes_with_the_way_to_act_on_it", async () => {
  const user = userEvent.setup();
  let refreshed = 0;
  vettingIs(supersededChainVetting);
  server.use(
    http.post("/api/v1/snapshots/:id/revet", () => {
      refreshed += 1;
      return HttpResponse.json({ snapshotId: 1, revoked: false });
    }),
  );
  renderReport();

  await user.click(
    await screen.findByRole("button", { name: "Re-run the vetting chain on snapshot 1" }),
  );
  expect(refreshed).toBe(1);
});

test("an_undetermined_comparison_is_not_dressed_up_as_either_answer", async () => {
  vettingIs(undeterminedChainVetting);
  renderReport();
  await screen.findByRole("region", { name: "Vetting of snapshot 1" });

  expect(await screen.findByText(/records no chain identity/)).toBeInTheDocument();
  // Not an alarm, and not a refresh prompt: the gateway does not know that anything is wrong.
  expect(
    screen.queryByText(/different chain than this marketplace runs now/),
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: /Re-run the vetting chain/ }),
  ).not.toBeInTheDocument();
});

/**
 * @SVCs SVC_GW_VETTING_0044, SVC_GW_APPROVAL_0022
 */
test("a_vendored_group_is_one_row_with_every_location_and_the_gate_names_them", async () => {
  vettingIs(vendoredVetting);
  renderReport();

  // The gate names each group at its locations, never a bare rule repeated per copy.
  const blocking = await screen.findByRole("list", { name: "Blocking findings" });
  const entries = within(blocking).getAllByRole("listitem");
  expect(entries).toHaveLength(2);
  expect(entries[0]).toHaveTextContent(
    "concealment-instruction at plugins/a/skills/x/SKILL.md:12, plugins/b/skills/x/SKILL.md:12, plugins/c/skills/x/SKILL.md:12 and 1 more",
  );
  expect(entries[1]).toHaveTextContent("concealment-instruction at plugins/e/skills/y/SKILL.md:4");

  // One action per group, labelled with what it does rather than a truncated "Waive…".
  expect(
    screen.getByRole("button", { name: "Waive all 4 locations of concealment-instruction" }),
  ).toHaveTextContent("Waive all 4 locations");
  expect(
    screen.getByRole("button", {
      name: "Waive finding concealment-instruction at plugins/e/skills/y/SKILL.md:4",
    }),
  ).toHaveTextContent("Waive finding");
  expect(screen.queryByText("Waive…")).not.toBeInTheDocument();
  expect(screen.getByText("3 more copies of the same content")).toBeInTheDocument();
});

/**
 * @SVCs SVC_GW_VETTING_0044
 */
test("waiving_a_group_defaults_to_that_group_and_posts_its_content", async () => {
  const user = userEvent.setup();
  const posted: unknown[] = [];
  vettingIs(vendoredVetting);
  server.use(
    http.post("/api/v1/snapshots/:id/waivers", async ({ request }) => {
      posted.push(await request.json());
      return HttpResponse.json({ id: 1 }, { status: 201 });
    }),
  );
  renderReport();

  await user.click(
    await screen.findByRole("button", { name: "Waive all 4 locations of concealment-instruction" }),
  );
  const scope = screen.getByLabelText("Scope");
  expect(scope).toHaveValue("group");
  // A path names one place, so a group of four is not offered one.
  expect(within(scope).getAllByRole("option").map((option) => option.textContent)).toEqual([
    "These 4 identical copies, in this snapshot",
    "Every concealment-instruction finding in this snapshot",
  ]);
  await user.type(screen.getByLabelText("Justification"), "vendored copy");
  await user.click(screen.getByRole("button", { name: "Record waiver for concealment-instruction" }));

  await vi.waitFor(() => expect(posted).toHaveLength(1));
  expect(posted[0]).toMatchObject({
    ruleId: "concealment-instruction",
    scope: "snapshot",
    content: "8cda9ad203d3da62a297cbe080a926db44328715",
    line: 12,
    justification: "vendored copy",
  });
});

/**
 * @SVCs SVC_GW_VETTING_0043
 */
test("a_pass_that_skipped_files_says_so_in_one_entry", async () => {
  vettingIs(vendoredVetting);
  renderReport();

  expect(await screen.findByText(/2 file\(s\) not scanned \(2 over the size limit\)/)).toBeInTheDocument();
  expect(
    screen.getAllByText(/2 file\(s\) not scanned: over the scan size limit: assets\/demo.mp4, assets\/hero.png/),
  ).toHaveLength(1);
});

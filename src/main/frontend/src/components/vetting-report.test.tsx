import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test, vi } from "vitest";
import {
  blockedVetting,
  supersededChainVetting,
  twoRuleVetting,
  undeterminedChainVetting,
  vendoredVetting,
} from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { MemoryRouter } from "react-router-dom";
import { contentsHref } from "@/lib/file-findings";
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

  // One action per group, its visible label naming the rule and its reach, never a bare "Waive…".
  expect(
    screen.getByRole("button", { name: "Waive concealment-instruction at 4 locations" }),
  ).toHaveTextContent("Waive concealment-instruction at 4 locations…");
  expect(
    screen.getByRole("button", {
      name: "Waive concealment-instruction at plugins/e/skills/y/SKILL.md:4",
    }),
  ).toHaveTextContent("Waive concealment-instruction…");
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
    await screen.findByRole("button", { name: "Waive concealment-instruction at 4 locations" }),
  );
  const scope = screen.getByLabelText("Scope");
  expect(scope).toHaveValue("group");
  // A path names one place, so a group of four is not offered one.
  expect(within(scope).getAllByRole("option").map((option) => option.textContent)).toEqual([
    "These 4 identical copies, in this snapshot",
    "Every concealment-instruction finding in this snapshot",
  ]);
  await user.type(screen.getByLabelText("Justification"), "vendored copy");
  await user.click(
    screen.getByRole("button", { name: "Record waiver for 4 findings of concealment-instruction" }),
  );

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
 * @SVCs SVC_GW_VETTING_0044
 */
test("the_waiver_form_states_how_many_findings_each_scope_covers_before_it_is_recorded", async () => {
  const user = userEvent.setup();
  vettingIs(vendoredVetting);
  renderReport();

  await user.click(
    await screen.findByRole("button", { name: "Waive concealment-instruction at 4 locations" }),
  );
  expect(screen.getByText(/covers 4 findings in this snapshot/)).toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "Record waiver for 4 findings of concealment-instruction" }),
  ).toHaveTextContent("Record waiver for 4 findings");

  // The rule across the snapshot reaches the other group too, and the count says so.
  await user.selectOptions(screen.getByLabelText("Scope"), "snapshot");
  expect(screen.getByText(/covers 5 findings in this snapshot/)).toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "Record waiver for 5 findings of concealment-instruction" }),
  ).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Cancel waiver for concealment-instruction" }));

  // A path reaches later snapshots, so the count is labelled as this snapshot's.
  await user.click(
    screen.getByRole("button", { name: "Waive concealment-instruction at plugins/e/skills/y/SKILL.md:4" }),
  );
  expect(screen.getByText(/covers 1 finding in this snapshot/)).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText("Scope"), "path");
  expect(
    screen.getByText(/covers 1 finding in this snapshot, and any under plugins\/e\/skills\/y\/SKILL\.md in later snapshots/),
  ).toBeInTheDocument();
});

/**
 * @SVCs SVC_GW_VETTING_0061
 */
test("blocking_groups_of_two_rules_are_waived_in_one_action_each_as_its_own_group", async () => {
  const user = userEvent.setup();
  const posted: Record<string, unknown>[] = [];
  vettingIs(twoRuleVetting);
  server.use(
    http.post("/api/v1/snapshots/:id/waivers", async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      posted.push(body);
      if (body.ruleId === "aws-access-key-id") {
        return HttpResponse.json(
          { title: "Waiver rejected", status: 400, detail: "no waiver for this rule today" },
          { status: 400 },
        );
      }
      return HttpResponse.json({ id: posted.length }, { status: 201 });
    }),
  );
  renderReport();

  const key = await screen.findByRole("checkbox", {
    name: "Select aws-access-key-id at plugins/hello/DEPLOY.md:5",
  });
  await user.click(key);
  await user.click(screen.getByRole("checkbox", { name: "Select concealment-instruction at 4 locations" }));

  const selection = screen.getByRole("region", { name: "Selected findings" });
  expect(selection).toHaveTextContent("2 groups, 5 findings selected");
  expect(selection).toHaveTextContent("aws-access-key-id, concealment-instruction");
  await user.click(within(selection).getByRole("button", { name: "Waive 2 selected groups" }));

  const record = within(selection).getByRole("button", { name: "Record 2 waivers for 5 findings" });
  expect(record).toBeDisabled();
  const justification = within(selection).getByLabelText("Justification");
  await user.type(justification, "   ");
  expect(record).toBeDisabled();
  await user.type(justification, "reviewed together");
  expect(record).toBeEnabled();
  await user.click(record);

  await vi.waitFor(() => expect(posted).toHaveLength(2));
  const expiry = posted[0]!.expiresAt;
  expect(posted).toEqual([
    {
      ruleId: "aws-access-key-id",
      scope: "snapshot",
      content: "3b18e512dba79e4c8300dd08aeb37f8e728b8dad",
      line: 5,
      justification: "reviewed together",
      expiresAt: expiry,
    },
    {
      ruleId: "concealment-instruction",
      scope: "snapshot",
      content: "8cda9ad203d3da62a297cbe080a926db44328715",
      line: 12,
      justification: "reviewed together",
      expiresAt: expiry,
    },
  ]);

  // The refusal is named, and the refused group is still selected for another try.
  expect(await within(selection).findByRole("alert")).toHaveTextContent(
    "Recorded 1 of 2 waivers. Refused: aws-access-key-id at plugins/hello/DEPLOY.md:5 — no waiver for this rule today",
  );
  expect(selection).toHaveTextContent("1 group, 1 finding selected");
  expect(key).toBeChecked();
  expect(
    screen.getByRole("checkbox", { name: "Select concealment-instruction at 4 locations" }),
  ).not.toBeChecked();
});

/**
 * @SVCs SVC_GW_VETTING_0007.2
 */
test("the_waiver_expiry_is_capped_at_ninety_days", async () => {
  const user = userEvent.setup();
  vettingIs(vendoredVetting);
  renderReport();

  await user.click(
    await screen.findByRole("button", { name: "Waive concealment-instruction at 4 locations" }),
  );
  const expiry = screen.getByLabelText("Expires on");
  const max = expiry.getAttribute("max") ?? "";
  // The last offered day, ended at 23:59:59Z as the form posts it, is within 90 days of now.
  const lastInstant = new Date(`${max}T23:59:59Z`).getTime();
  expect(lastInstant).toBeLessThanOrEqual(Date.now() + 90 * 86_400_000);
  expect(lastInstant).toBeGreaterThan(Date.now() + 89 * 86_400_000);

  await user.type(screen.getByLabelText("Justification"), "vendored copy");
  const record = screen.getByRole("button", {
    name: "Record waiver for 4 findings of concealment-instruction",
  });
  expect(record).toBeEnabled();
  fireEvent.change(expiry, { target: { value: "2099-01-01" } });
  expect(record).toBeDisabled();
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

function renderLinkedReport() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <VettingReport snapshotId={1} locationHref={(location) => contentsHref(1, location)} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * @SVCs SVC_GW_APPROVAL_0030
 */
test("every_location_links_to_its_file_and_line_in_contents", async () => {
  vettingIs(vendoredVetting);
  renderLinkedReport();

  const blocking = await screen.findByRole("list", { name: "Blocking findings" });
  const entry = within(blocking).getAllByRole("listitem")[0]!;
  // The text is unchanged: the same locations, the same truncation, now each one a link.
  expect(entry).toHaveTextContent(
    "concealment-instruction at plugins/a/skills/x/SKILL.md:12, plugins/b/skills/x/SKILL.md:12, plugins/c/skills/x/SKILL.md:12 and 1 more",
  );
  expect(within(entry).getByRole("link", { name: "plugins/b/skills/x/SKILL.md:12" })).toHaveAttribute(
    "href",
    "/?snapshot=1&tab=contents&path=plugins%2Fb%2Fskills%2Fx%2FSKILL.md&line=12",
  );
  // A group's other copies link too, each to its own file.
  expect(screen.getAllByRole("link", { name: "plugins/d/skills/x/SKILL.md:12" }).length).toBeGreaterThan(0);
});

/**
 * @SVCs SVC_GW_APPROVAL_0030
 */
test("without_a_link_target_locations_stay_text", async () => {
  vettingIs(vendoredVetting);
  renderReport();

  await screen.findByRole("list", { name: "Blocking findings" });
  expect(screen.queryByRole("link", { name: /SKILL\.md/ })).not.toBeInTheDocument();
});

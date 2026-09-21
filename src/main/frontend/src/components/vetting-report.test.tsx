import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test } from "vitest";
import {
  blockedVetting,
  supersededChainVetting,
  undeterminedChainVetting,
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

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { heldSnapshot, marketplace } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { ReviewQueuePage } from "./review-queue";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ReviewQueuePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * Every snapshot awaiting a decision, across marketplaces, newest first; each opens on its
 * marketplace's review; nothing here decides.
 *
 * @SVCs SVC_GW_INGEST_0037
 */
test("the_queue_lists_what_awaits_across_marketplaces_and_decides_nothing", async () => {
  server.use(
    http.get("/api/v1/marketplaces", () =>
      HttpResponse.json([
        marketplace,
        {
          id: 2,
          name: "other",
          url: "https://example.com/other.git",
          snapshots: [
            { ...heldSnapshot, id: 40, marketplaceId: 2, sha: "4".repeat(40), createdAt: "2026-09-01T00:00:00Z", ingestedBy: "bob" },
          ],
        },
        { id: 3, name: "quiet", url: "https://example.com/quiet.git", snapshots: [] },
      ]),
    ),
  );
  renderPage();
  const table = await screen.findByRole("table");
  const rows = within(table).getAllByRole("row").slice(1);
  // Held snapshot 1 and revoked snapshot 3 of corp-marketplace, and other's snapshot 40.
  expect(rows).toHaveLength(3);
  // Newest first: other's snapshot 40 was ingested last.
  expect(within(rows[0]!).getByRole("link", { name: "other" })).toBeInTheDocument();
  expect(rows[0]).toHaveTextContent("by bob");
  expect(
    within(rows[0]!).getByRole("link", { name: `Review snapshot ${"4".repeat(12)} of other` }),
  ).toHaveAttribute("href", "/marketplaces/other?snapshot=40");
  expect(screen.queryByText("quiet")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /Approve|Reject/ })).not.toBeInTheDocument();
});

test("an_empty_queue_says_so", async () => {
  server.use(http.get("/api/v1/marketplaces", () => HttpResponse.json([])));
  renderPage();
  expect(await screen.findByText(/Nothing awaits a decision\./)).toBeInTheDocument();
});

test("a_queue_that_cannot_be_read_is_an_error", async () => {
  server.use(http.get("/api/v1/marketplaces", () => HttpResponse.json({ detail: "boom" }, { status: 500 })));
  renderPage();
  expect(await screen.findByRole("alert")).toBeInTheDocument();
});

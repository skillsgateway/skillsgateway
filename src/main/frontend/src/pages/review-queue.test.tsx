import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

/**
 * The queue says how long each snapshot has waited and how many findings its vetting run recorded,
 * and the reader orders it by waiting time or by marketplace; the headers say which order is in force.
 *
 * @SVCs SVC_GW_INGEST_0037
 */
test("the_queue_shows_waiting_and_findings_and_orders_by_either_column", async () => {
  server.use(
    http.get("/api/v1/marketplaces", () =>
      HttpResponse.json([
        {
          id: 1,
          name: "zeta",
          url: "https://example.com/zeta.git",
          snapshots: [{ ...heldSnapshot, id: 11, marketplaceId: 1, sha: "a".repeat(40), createdAt: "2026-09-03T00:00:00Z" }],
        },
        {
          id: 2,
          name: "alpha",
          url: "https://example.com/alpha.git",
          snapshots: [{ ...heldSnapshot, id: 22, marketplaceId: 2, sha: "b".repeat(40), createdAt: "2026-09-01T00:00:00Z" }],
        },
      ]),
    ),
    http.get("/api/v1/snapshots/:id/vetting", ({ params }) => {
      const findings = params.id === "11" ? 3 : 0;
      return HttpResponse.json({
        snapshotId: Number(params.id),
        outcome: findings > 0 ? "blocked" : "clear",
        run: {
          runId: 1,
          verdicts: [
            {
              vetter: "secret-scan",
              state: findings > 0 ? "fail" : "pass",
              findings: Array.from({ length: findings }, (_, i) => ({ rule: "r", severity: "high", message: "m", location: `f:${i}` })),
            },
          ],
        },
      });
    }),
  );
  const user = userEvent.setup();
  renderPage();
  const table = await screen.findByRole("table");
  const names = () =>
    within(table)
      .getAllByRole("row")
      .slice(1)
      .map((row) => within(row).getAllByRole("link")[0]!.textContent);

  // Newest first by default, and the ingestion column says so.
  expect(names()).toEqual(["zeta", "alpha"]);
  const ingested = screen.getByRole("columnheader", { name: "Ingested" });
  expect(ingested).toHaveAttribute("aria-sort", "descending");
  expect(screen.getByRole("columnheader", { name: "Marketplace" })).toHaveAttribute("aria-sort", "none");

  // Finding counts, read from each snapshot's run; waiting time is relative, the instant a hover away.
  const [zeta, alpha] = within(table).getAllByRole("row").slice(1);
  await waitFor(() => expect(within(zeta!).getByText("3")).toBeInTheDocument());
  await waitFor(() => expect(within(alpha!).getByText("0")).toBeInTheDocument());
  const stamp = within(zeta!).getByTitle(/2026-09-03T00:00:00Z/);
  expect(stamp.textContent).toMatch(/ago/);

  // Oldest first: what has waited longest leads.
  await user.click(within(ingested).getByRole("button", { name: "Ingested" }));
  expect(names()).toEqual(["alpha", "zeta"]);
  expect(ingested).toHaveAttribute("aria-sort", "ascending");

  await user.click(screen.getByRole("button", { name: "Marketplace" }));
  expect(names()).toEqual(["alpha", "zeta"]);
  expect(screen.getByRole("columnheader", { name: "Marketplace" })).toHaveAttribute("aria-sort", "ascending");
  await user.click(screen.getByRole("button", { name: "Marketplace" }));
  expect(names()).toEqual(["zeta", "alpha"]);
});

/** Grouped by marketplace in either direction, each marketplace's snapshots stay newest first. */
test("ordering_by_marketplace_keeps_each_marketplace_newest_first_both_ways", async () => {
  const held = (id: number, marketplaceId: number, createdAt: string) => ({
    ...heldSnapshot,
    id,
    marketplaceId,
    sha: String(id % 10).repeat(40),
    createdAt,
  });
  server.use(
    http.get("/api/v1/marketplaces", () =>
      HttpResponse.json([
        {
          id: 1,
          name: "alpha",
          url: "https://example.com/alpha.git",
          // Same second, one with a fraction: compared as instants, not as strings.
          snapshots: [held(11, 1, "2026-09-01T00:00:00Z"), held(12, 1, "2026-09-01T00:00:00.500Z")],
        },
        { id: 2, name: "zeta", url: "https://example.com/zeta.git", snapshots: [held(21, 2, "2026-09-02T00:00:00Z")] },
      ]),
    ),
  );
  const user = userEvent.setup();
  renderPage();
  const table = await screen.findByRole("table");
  const ids = () =>
    within(table)
      .getAllByRole("row")
      .slice(1)
      .map((row) => within(row).getByRole("link", { name: /^Review snapshot/ }).getAttribute("href"));
  const at = (id: number, name: string) => `/marketplaces/${name}?snapshot=${id}`;

  expect(ids()).toEqual([at(21, "zeta"), at(12, "alpha"), at(11, "alpha")]);
  await user.click(screen.getByRole("button", { name: "Marketplace" }));
  expect(ids()).toEqual([at(12, "alpha"), at(11, "alpha"), at(21, "zeta")]);
  await user.click(screen.getByRole("button", { name: "Marketplace" }));
  expect(ids()).toEqual([at(21, "zeta"), at(12, "alpha"), at(11, "alpha")]);
});

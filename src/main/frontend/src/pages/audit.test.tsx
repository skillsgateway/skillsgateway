import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { server } from "@/test/msw-server";
import { AuditPage } from "./audit";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuditPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

test("the_ledger_download_is_the_page_action_and_the_sinks_are_linked", async () => {
  renderPage();
  // The download link points at the NDJSON stream, not at a rendered table.
  expect(screen.getByRole("link", { name: /Download ledger/ })).toHaveAttribute(
    "href",
    "/api/v1/audit/export",
  );
  // The sinks are an integration now; the audit page says how many there are and where.
  expect(await screen.findByText(/Pushed onwards to 1 sink/)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Audit sinks" })).toHaveAttribute("href", "/integrations/sinks");
  expect(screen.queryByLabelText("Sink name")).not.toBeInTheDocument();
});

/**
 * A blocked vetting row must read as blocked — the exact gap #221/#224 names: the same verdict
 * that paints the marketplace red is invisible in the ledger. The status is derived from the
 * event and its `outcome=` detail, and the marketplace links to its detail page.
 */
test("a_blocked_vetting_row_is_flagged_and_the_marketplace_links_to_its_detail", async () => {
  server.use(
    http.get("/api/v1/audit", () =>
      HttpResponse.json({ nextBefore: null, entries: [
        {
          id: 1,
          ts: "2026-08-14T10:00:01Z",
          source: "admin",
          principal: "vetting",
          marketplace: "ri-2",
          event: "vetting-completed",
          sha: "aaaabbbbccccddddeeeeffff0000111122223333",
          // The server writes the outcome lower-cased in the free-text detail.
          detail: "trigger=ingestion; outcome=blocked; vetters=3; chain=secret-scan@1,prompt-injection@1,license-scan@1",
        },
      ] }),
    ),
  );
  renderPage();

  const row = await screen.findByRole("row", { name: /vetting-completed/ });
  expect(within(row).getByText("blocked")).toBeInTheDocument();
  expect(within(row).getByRole("link", { name: "ri-2" })).toHaveAttribute(
    "href",
    "/marketplaces/ri-2",
  );
});

/**
 * The per-column filters complete from the values actually present rather than being typed
 * blind: each free-text filter is backed by a <datalist> of the distinct values in the loaded
 * rows, and the marketplace column also completes from the authoritative marketplaces list.
 */
test("column_filters_offer_completion_from_present_values", async () => {
  server.use(
    http.get("/api/v1/audit", () =>
      HttpResponse.json({ nextBefore: null, entries: [
        {
          id: 1,
          ts: "2026-08-14T10:00:01Z",
          principal: "vetting",
          marketplace: "ri-2",
          event: "vetting-completed",
          sha: "aaaabbbbccccddddeeeeffff0000111122223333",
        },
        {
          id: 2,
          ts: "2026-08-14T10:00:02Z",
          principal: "ci-bot",
          marketplace: "-",
          event: "fetch-served",
          sha: "-",
        },
      ] }),
    ),
    http.get("/api/v1/marketplaces", () =>
      HttpResponse.json([{ name: "corp-marketplace" }, { name: "ri-2" }]),
    ),
  );
  renderPage();

  const eventFilter = await screen.findByLabelText("Filter by event");
  expect(eventFilter).toHaveAttribute("list", "facet-event");
  const eventList = document.getElementById("facet-event");
  const eventOptions = Array.from(eventList?.querySelectorAll("option") ?? []).map(
    (option) => option.value,
  );
  // Distinct, sorted, and the "-" placeholder dropped.
  expect(eventOptions).toEqual(["fetch-served", "vetting-completed"]);

  // The marketplace column unions the authoritative list (loaded async) with any name only in
  // the rows.
  await waitFor(() => {
    const marketplaceList = document.getElementById("facet-marketplace");
    const marketplaceOptions = Array.from(marketplaceList?.querySelectorAll("option") ?? []).map(
      (option) => option.value,
    );
    expect(marketplaceOptions).toEqual(["corp-marketplace", "ri-2"]);
  });
});

/**
 * The ledger opens newest first. The API answers in ledger order — oldest first — so an
 * administrator who has just switched a vetter off would otherwise find their entry at the bottom
 * of the table, or on its last page, and read the page as not having recorded the change at all.
 */
test("the_ledger_opens_with_the_newest_entry_first", async () => {
  server.use(
    http.get("/api/v1/audit", () =>
      HttpResponse.json({ nextBefore: null, entries: [
        {
          id: 1,
          ts: "2026-09-20T10:00:00.000Z",
          principal: "dev",
          marketplace: "clean-demo",
          event: "marketplace-registered",
        },
        {
          id: 2,
          ts: "2026-09-20T14:37:29.827Z",
          principal: "dev",
          marketplace: "-",
          event: "vetter-disabled",
          detail: "vetter=secret-scan scope=global enabled=false",
        },
      ] }),
    ),
  );
  renderPage();

  await screen.findByText("vetter-disabled");
  // Row order, not row index: the page renders the sinks table above the ledger.
  const rows = screen.getAllByRole("row").map((row) => row.textContent ?? "");
  const newest = rows.findIndex((text) => text.includes("vetter-disabled"));
  const oldest = rows.findIndex((text) => text.includes("marketplace-registered"));
  expect(newest).toBeGreaterThan(-1);
  expect(newest).toBeLessThan(oldest);
});

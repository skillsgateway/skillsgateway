import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { auditPage, ledgerEntries } from "@/test/msw-handlers";
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
 * The ledger opens newest first. The API answers newest first too (GW_AUDIT_0008), but the table
 * sorts the entries it has loaded itself, across every page fetched; the fixture below is in
 * ledger order — oldest first — so it is the table's own sort that puts an administrator's
 * just-made change at the top rather than at the bottom or on the last page.
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

/**
 * The page reaches past the entries it first loaded: the control passes the last page's
 * `nextBefore` back and the older entries join the table. Without it the download was the only
 * way to an entry older than the newest page.
 *
 * @SVCs SVC_GW_AUDIT_0008
 */
test("load_older_entries_fetches_the_next_page_through_the_cursor", async () => {
  const user = userEvent.setup();
  const befores: string[] = [];
  server.use(
    http.get("/api/v1/audit", ({ request }) => {
      const url = new URL(request.url);
      befores.push(url.searchParams.get("before") ?? "");
      url.searchParams.set("limit", "3");
      return HttpResponse.json(auditPage(ledgerEntries, url));
    }),
  );
  renderPage();

  await screen.findByText("upload-pack");
  expect(screen.getByText("3 of 3 loaded entries")).toBeInTheDocument();
  expect(screen.queryByText("snapshot-ingested")).not.toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Load older entries" }));
  expect(await screen.findByText("snapshot-ingested")).toBeInTheDocument();
  expect(screen.getByText("6 of 6 loaded entries")).toBeInTheDocument();
  expect(befores).toEqual(["", "4"]);
  // Six entries at three a page: the second page was full, so the cursor asks once more, finds
  // nothing older, and the control gives way to the statement that the oldest entry is loaded.
  await user.click(screen.getByRole("button", { name: "Load older entries" }));
  expect(await screen.findByText(/nothing older is recorded/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Load older entries" })).not.toBeInTheDocument();
});


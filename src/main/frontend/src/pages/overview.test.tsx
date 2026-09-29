import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { server } from "@/test/msw-server";
import { OverviewPage } from "./overview";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <OverviewPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function ledgerOf(total: number, totalIsEstimate: boolean) {
  const requests: string[] = [];
  server.use(
    http.get("/api/v1/audit", ({ request }) => {
      requests.push(new URL(request.url).search);
      // One entry on the page, however many the ledger holds: the card must read the total.
      return HttpResponse.json({ entries: [{ id: total }], nextBefore: total - 1, total, totalIsEstimate });
    }),
  );
  return requests;
}

/**
 * The ledger card counts the ledger, not the page a browse read returns, and does not call every
 * entry a fetch: administrative actions and vetting bookkeeping are entries too.
 *
 * @SVCs SVC_GW_AUDIT_0008
 */
test("the_ledger_card_counts_every_entry_from_the_server_total", async () => {
  const requests = ledgerOf(1234, false);
  renderPage();

  expect(await screen.findByText("1,234 ledger entries")).toBeInTheDocument();
  expect(screen.queryByText(/recorded fetches/)).not.toBeInTheDocument();
  // A count costs one entry, not a page of a thousand.
  expect(requests).toEqual(["?limit=1"]);
});

/** Past the size where the server estimates rather than counts, the card says so. */
test("an_estimated_total_reads_as_about", async () => {
  ledgerOf(2_500_000, true);
  renderPage();

  expect(await screen.findByText("about 2,500,000 ledger entries")).toBeInTheDocument();
});

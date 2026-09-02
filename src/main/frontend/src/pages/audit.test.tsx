import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

test("export_download_and_sink_positions_are_shown", async () => {
  renderPage();
  // The download link points at the NDJSON stream, not at a rendered table.
  expect(screen.getByRole("link", { name: /Download ledger/ })).toHaveAttribute(
    "href",
    "/api/audit/export",
  );
  // The sink row carries its target and its position in the ledger.
  expect(await screen.findByRole("row", { name: /siem.*42.*3 entries/ })).toBeInTheDocument();
});

/**
 * A blocked vetting row must read as blocked — the exact gap #221/#224 names: the same verdict
 * that paints the marketplace red is invisible in the ledger. The status is derived from the
 * event and its `outcome=` detail, and the marketplace links to its detail page.
 */
test("a_blocked_vetting_row_is_flagged_and_the_marketplace_links_to_its_detail", async () => {
  server.use(
    http.get("/api/audit", () =>
      HttpResponse.json([
        {
          id: 1,
          ts: "2026-08-14T10:00:01Z",
          source: "admin",
          principal: "vetting",
          marketplace: "ri-2",
          event: "vetting-completed",
          sha: "aaaabbbbccccddddeeeeffff0000111122223333",
          // The server writes the outcome lower-cased in the free-text detail.
          detail: "trigger=ingestion; outcome=blocked; connectors=3; chain=secret-scan@1,prompt-injection@1,license-scan@1",
        },
      ]),
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

test("add_sink_is_disabled_until_the_name_and_url_are_valid", async () => {
  const user = userEvent.setup();
  renderPage();
  const nameField = await screen.findByLabelText("Sink name");
  const urlField = screen.getByLabelText("Target URL");
  const addButton = screen.getByRole("button", { name: "Add sink" });

  expect(addButton).toBeDisabled();

  await user.type(nameField, "   ");
  expect(addButton).toBeDisabled();

  await user.clear(nameField);
  await user.type(nameField, "new-siem");
  expect(addButton).toBeDisabled();

  await user.type(urlField, "siem.example.com/ingest");
  expect(addButton).toBeDisabled();

  await user.clear(urlField);
  await user.type(urlField, "https://siem.example.com/ingest");
  expect(addButton).toBeEnabled();
});

test("created_sink_secret_is_shown_once_in_a_dialog", async () => {
  const user = userEvent.setup();
  renderPage();
  await user.type(await screen.findByLabelText("Sink name"), "new-siem");
  await user.type(screen.getByLabelText("Target URL"), "https://siem.example.com/ingest");
  await user.click(screen.getByRole("button", { name: "Add sink" }));
  expect(await screen.findByTestId("sink-secret")).toHaveTextContent("whsec_sink_shown_once");
  await user.click(screen.getByRole("button", { name: "Done" }));
  expect(screen.queryByTestId("sink-secret")).not.toBeInTheDocument();
});

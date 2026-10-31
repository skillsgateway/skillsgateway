import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { expect, test, vi } from "vitest";
import { bulkPartialFailure, chainOverrides, vetterToggles } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { VettingPage } from "./vetting";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <VettingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function asAdmin() {
  server.use(
    http.get("/api/v1/me", () =>
      HttpResponse.json({
        username: "alice",
        roles: [{ role: "admin", source: "config" }],
        claimsTruncated: false,
      }),
    ),
  );
}

/**
 * A session holding no role. The page must refuse rather than render an empty shell, and must not
 * reach for the data behind it — the server would refuse it anyway, but a refused request is a 403
 * in the console for a reader who has done nothing wrong.
 */
test("a_session_without_the_administrative_role_is_refused_the_page", async () => {
  server.use(
    http.get("/api/v1/me", () =>
      HttpResponse.json({ username: "bob", roles: [], claimsTruncated: false }),
    ),
  );
  renderPage();
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "This page needs the administrative role.",
  );
  expect(screen.queryByText("The default chain")).not.toBeInTheDocument();
});

/**
 * The refusal names the role and points only where the reader can go: a snapshot's vetting report,
 * which every session reads, and not a marketplace's own chain, which is administrator-only too.
 *
 * @SVCs SVC_GW_AUTH_0077
 */
test("the_vetting_chain_refusal_points_only_to_what_the_reader_can_read", async () => {
  server.use(
    http.get("/api/v1/me", () =>
      HttpResponse.json({
        username: "bob",
        roles: [{ role: "approver", marketplace: "corp-marketplace", source: "grant" }],
        claimsTruncated: false,
      }),
    ),
  );
  renderPage();
  const alert = await screen.findByRole("alert");
  expect(alert).toHaveTextContent("This page needs the administrative role.");
  const card = alert.parentElement!;
  expect(card).toHaveTextContent("Vetting tab");
  expect(card).not.toHaveTextContent(/chain of a marketplace/);
  expect(screen.getByRole("link", { name: "Marketplaces" })).toHaveAttribute("href", "/marketplaces");
});

test("an_administrator_reads_the_default_chain_and_the_marketplaces_that_depart_from_it", async () => {
  asAdmin();
  server.use(
    http.get("/api/v1/vetting/chain-settings", () => HttpResponse.json(chainOverrides)),
    http.get("/api/v1/vetting/vetter-toggles", () => HttpResponse.json(vetterToggles)),
  );
  renderPage();

  expect(await screen.findByText("The default chain")).toBeInTheDocument();
  // The override the marketplace holds, named as what it overrides rather than as a state.
  expect(await screen.findByText("mode: stop-after-fail")).toBeInTheDocument();
  expect(
    await screen.findByRole("button", { name: "Clear override (corp-marketplace)" }),
  ).toBeEnabled();
});

/**
 * The adversarial half: a bulk request in which one marketplace was refused must not read as a
 * success. The server answers 207 and the page reads `results`, so the outcome is an alert naming
 * the refusal rather than a count of what did work.
 */
test("a_bulk_change_that_refused_a_marketplace_is_reported_as_a_failure", async () => {
  asAdmin();
  const sent = vi.fn();
  server.use(
    http.post("/api/v1/vetting/chain-settings/bulk", async ({ request }) => {
      sent(await request.json());
      return HttpResponse.json(bulkPartialFailure, { status: 207 });
    }),
  );
  renderPage();

  const selectAll = await screen.findByRole("checkbox", { name: "All marketplaces" });
  await userEvent.click(selectAll);

  // Nothing is applied before the plan has been read.
  expect(screen.queryByRole("button", { name: "Apply to these marketplaces" })).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: "Review the change" }));
  await userEvent.click(
    await screen.findByRole("button", { name: "Apply to these marketplaces" }),
  );

  await waitFor(() =>
    expect(sent).toHaveBeenCalledWith(
      expect.objectContaining({ action: "set-mode", mode: "stop-after-fail" }),
    ),
  );
  const alerts = await screen.findAllByRole("alert");
  const outcome = alerts.find((element) => element.textContent?.includes("refused"));
  expect(outcome).toBeDefined();
  expect(outcome).toHaveTextContent("1 refused, 1 applied");
  expect(outcome).toHaveTextContent("marketplace 'partner-marketplace' not found");
});

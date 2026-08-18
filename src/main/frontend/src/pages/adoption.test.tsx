import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test } from "vitest";
import { server } from "@/test/msw-server";
import { AdoptionPage } from "./adoption";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AdoptionPage />
    </QueryClientProvider>,
  );
}

test("adoption_report_shows_marketplace_totals_and_the_per_sha_breakdown", async () => {
  renderPage();
  // The marketplace card carries the window totals... (the name also appears in the staleness
  // table below, so the assertion is on presence, not uniqueness)
  expect((await screen.findAllByText("corp-marketplace")).length).toBeGreaterThan(0);
  // The window total appears twice on purpose: the page-level chip and the card's chip.
  expect(screen.getAllByText("14")).toHaveLength(2);
  // ...and the breakdown marks the served tip current and the older SHA superseded.
  const tipRow = screen.getByRole("row", { name: /aaaabbbbcccc.*current/ });
  expect(tipRow).toBeInTheDocument();
  const oldRow = screen.getByRole("row", { name: /111122223333.*superseded/ });
  expect(oldRow).toBeInTheDocument();
});

test("stale_identities_show_the_served_tip_or_that_the_marketplace_stopped_serving", async () => {
  renderPage();
  const behindTheTip = await screen.findByRole("row", { name: /team-payments/ });
  expect(behindTheTip).toHaveTextContent("aaaabbbbcccc");
  // A retracted marketplace has no tip: the row says so instead of showing a SHA.
  const retracted = screen.getByRole("row", { name: /ci-runner/ });
  expect(retracted).toHaveTextContent("not serving");
});

test("window_selection_is_a_pressed_button_group_defaulting_to_30_days", async () => {
  const user = userEvent.setup();
  renderPage();
  const thirty = await screen.findByRole("button", { name: "30 days" });
  expect(thirty).toHaveAttribute("aria-pressed", "true");
  const seven = screen.getByRole("button", { name: "7 days" });
  expect(seven).toHaveAttribute("aria-pressed", "false");
  await user.click(seven);
  expect(seven).toHaveAttribute("aria-pressed", "true");
  expect(thirty).toHaveAttribute("aria-pressed", "false");
});

test("empty_ledger_and_no_stale_identities_render_explicit_empty_states", async () => {
  server.use(
    http.get("/api/adoption", () => HttpResponse.json([])),
    http.get("/api/adoption/staleness", () => HttpResponse.json([])),
  );
  renderPage();
  expect(await screen.findByText(/No fetches in the last 30 days/)).toBeInTheDocument();
  expect(screen.getByText("Every identity is on the served tip.")).toBeInTheDocument();
});

test("a_failing_report_renders_an_alert_not_a_blank_page", async () => {
  server.use(http.get("/api/adoption", () => HttpResponse.json({}, { status: 500 })));
  renderPage();
  expect(await screen.findByRole("alert")).toBeInTheDocument();
});

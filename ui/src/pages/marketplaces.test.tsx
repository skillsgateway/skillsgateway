import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { MarketplacesPage } from "./marketplaces";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MarketplacesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

test("lists_registered_marketplaces_with_held_snapshots", async () => {
  renderPage();
  expect(await screen.findByText("corp-marketplace")).toBeInTheDocument();
  expect(screen.getByText("held")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Approve snapshot 1" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Reject snapshot 1" })).toBeInTheDocument();
});

test("register_dialog_rejects_invalid_name_and_malformed_url", async () => {
  const user = userEvent.setup();
  renderPage();
  await user.click(await screen.findByRole("button", { name: "Register marketplace" }));
  await user.type(screen.getByLabelText("Name"), "Bad Name");
  await user.type(screen.getByLabelText("Clone URL"), "not-a-url");
  await user.click(screen.getByRole("button", { name: "Register" }));
  const alerts = await screen.findAllByRole("alert");
  expect(alerts.length).toBeGreaterThanOrEqual(2);
});

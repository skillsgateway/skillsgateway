import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
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

/**
 * The blocked snapshot cannot be approved by clicking through: the dialog shows the failing
 * connector's finding and keeps the confirm control disabled until a reason is typed.
 */
test("approving_a_blocked_snapshot_shows_the_findings_and_requires_a_reason", async () => {
  const user = userEvent.setup();
  renderPage();
  await user.click(await screen.findByRole("button", { name: "Approve snapshot 1" }));

  const dialog = await screen.findByRole("dialog");
  expect((await within(dialog).findAllByText("secret-scan")).length).toBeGreaterThan(0);
  expect(await within(dialog).findByText(/an AWS access key id is committed/)).toBeInTheDocument();

  const confirm = within(dialog).getByRole("button", { name: "Confirm approval of snapshot 1" });
  expect(confirm).toBeDisabled();

  await user.type(within(dialog).getByLabelText("Reason for approving anyway"), "documented dummy key");
  expect(confirm).toBeEnabled();
  expect(dialog).toBeInTheDocument();
});

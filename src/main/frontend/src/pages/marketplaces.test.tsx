import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { toast } from "sonner";
import { expect, test, vi } from "vitest";
import { server } from "@/test/msw-server";
import { MarketplacesPage } from "./marketplaces";

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}));

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

/**
 * The list is an index: it says how many snapshots await a decision and links to where the
 * evidence is, and it offers no decision of its own.
 *
 * @SVCs SVC_GW_INGEST_0037
 */
test("lists_marketplaces_with_their_awaiting_count_and_no_decision_controls", async () => {
  renderPage();
  expect(await screen.findByText("corp-marketplace")).toBeInTheDocument();
  // The latest snapshot's state is shown on the row.
  expect(screen.getByText("held")).toBeInTheDocument();
  // Snapshot 1 is held and snapshot 3 revoked: both await a decision.
  expect(screen.getByRole("link", { name: "2 awaiting a decision in corp-marketplace" })).toHaveAttribute(
    "href",
    "/marketplaces/corp-marketplace",
  );
  expect(screen.queryByRole("button", { name: /Expand corp-marketplace/ })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /^Approve snapshot/ })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /^Reject snapshot/ })).not.toBeInTheDocument();
});

test("register_is_disabled_until_the_name_and_url_are_valid", async () => {
  const user = userEvent.setup();
  renderPage();
  await user.click(await screen.findByRole("button", { name: "Register marketplace" }));
  const nameField = screen.getByLabelText("Name");
  const urlField = screen.getByLabelText("Clone URL");
  const register = screen.getByRole("button", { name: "Register" });

  expect(register).toBeDisabled();

  await user.type(nameField, "corp-two");
  expect(register).toBeDisabled();

  await user.type(urlField, "not-a-url");
  expect(register).toBeDisabled();

  await user.clear(urlField);
  await user.type(urlField, "https://github.com/org/marketplace.git");
  expect(register).toBeEnabled();

  // A name the server's pattern rejects disables it again.
  await user.clear(nameField);
  await user.type(nameField, "Bad Name");
  expect(register).toBeDisabled();
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
 * vetter's finding, keeps the confirm control disabled, and offers the only way past it —
 * accepting that finding with a justification and an expiry.
 */
/**
 * Registering an already-registered upstream is legitimate but usually a mistake, so the form
 * warns and holds Register shut until the collision is acknowledged — a deliberate choice, not a
 * silent one. The `.git` suffix and a trailing slash do not hide the collision.
 */
test("register_warns_and_gates_on_a_duplicate_clone_url", async () => {
  const user = userEvent.setup();
  renderPage();
  await user.click(await screen.findByRole("button", { name: "Register marketplace" }));
  await user.type(screen.getByLabelText("Name"), "corp-copy");
  // corp-marketplace is registered at https://github.com/corp/marketplace.git in the fixtures.
  await user.type(screen.getByLabelText("Clone URL"), "https://github.com/corp/marketplace");

  const register = screen.getByRole("button", { name: "Register" });
  expect(register).toBeDisabled();
  expect(await screen.findByText(/already registered as/)).toBeInTheDocument();

  await user.click(screen.getByRole("checkbox", { name: "Register anyway" }));
  expect(register).toBeEnabled();
});

/**
 * The client-side check above only ever sees the marketplace list already loaded on this page —
 * the server checks again, authoritatively, against every marketplace, and returns what it found
 * in the registration response. A registration this page's own check missed (a race, a stale
 * list) still has to reach the operator, so a warning in that response is shown here too.
 */
test("a_warning_in_the_registration_response_is_shown_even_when_the_client_missed_it", async () => {
  const user = userEvent.setup();
  server.use(
    http.post("/api/v1/marketplaces", () =>
      HttpResponse.json(
        {
          id: 9,
          name: "corp-second",
          url: "https://github.com/corp/other.git",
          warnings: ["url already registered as corp-marketplace"],
        },
        { status: 201 },
      ),
    ),
  );
  renderPage();
  await user.click(await screen.findByRole("button", { name: "Register marketplace" }));
  await user.type(screen.getByLabelText("Name"), "corp-second");
  await user.type(screen.getByLabelText("Clone URL"), "https://github.com/corp/other.git");
  await user.click(screen.getByRole("button", { name: "Register" }));

  await waitFor(() =>
    expect(toast.warning).toHaveBeenCalledWith("url already registered as corp-marketplace"),
  );
});

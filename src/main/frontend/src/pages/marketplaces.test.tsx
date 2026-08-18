import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { tooYoung } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
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

/**
 * The cooling-off window (GW_0073) as a reviewer meets it: the control is shut and says when it
 * opens, rather than opening a dialog that would only refuse. Nothing in the portal can shorten
 * the wait — that is the point of the control — so the copy says what happens instead of offering
 * a way past it.
 *
 * Untagged on purpose: SVC_GW_0073 is verified by the Java suite, and only Playwright results are
 * matched back to SVC ids (vitest classnames are not normalised to the tag FQN style). Tagging it
 * here would register a verification the traceability gate could never see pass.
 */
test("approve_is_disabled_with_the_remaining_time_inside_the_cooling_off_window", async () => {
  server.use(http.get("/api/snapshots/:id/release-age", () => HttpResponse.json(tooYoung)));
  renderPage();

  const approve = await screen.findByRole("button", { name: "Approve snapshot 1" });
  await waitFor(() => expect(approve).toHaveTextContent("Eligible in 2d 4h"));
  expect(approve).toBeDisabled();
  // Rejecting is never age-gated: suspicious content must be refusable at once.
  expect(screen.getByRole("button", { name: "Reject snapshot 1" })).toBeEnabled();
});

test("approve_is_offered_normally_once_the_window_has_passed", async () => {
  renderPage();

  const approve = await screen.findByRole("button", { name: "Approve snapshot 1" });
  // Never shut merely because the answer has not arrived: the server is the gate, and a portal
  // that guessed "not eligible" while loading would block every approval on a slow request.
  expect(approve).toBeEnabled();
  await waitFor(() => expect(approve).toHaveTextContent("Approve"));
  expect(approve).toBeEnabled();
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
 * connector's finding, keeps the confirm control disabled, and offers the only way past it —
 * accepting that finding with a justification and an expiry.
 */
test("approving_a_blocked_snapshot_shows_the_findings_and_offers_a_waiver", async () => {
  const user = userEvent.setup();
  renderPage();
  await user.click(await screen.findByRole("button", { name: "Approve snapshot 1" }));

  const dialog = await screen.findByRole("dialog");
  expect((await within(dialog).findAllByText("secret-scan")).length).toBeGreaterThan(0);
  expect(await within(dialog).findByText(/an AWS access key id is committed/)).toBeInTheDocument();

  // No reason field exists any more, and no amount of typing enables the button.
  const confirm = within(dialog).getByRole("button", { name: "Confirm approval of snapshot 1" });
  expect(confirm).toBeDisabled();
  expect(within(dialog).queryByLabelText("Reason for approving anyway")).not.toBeInTheDocument();

  // The waiver form demands a justification before it will record anything.
  await user.click(within(dialog).getByRole("button", { name: "Waive finding aws-access-key-id" }));
  const record = within(dialog).getByRole("button", { name: "Record waiver for aws-access-key-id" });
  expect(record).toBeDisabled();
  expect(within(dialog).getByLabelText("Expires on")).toHaveValue();

  await user.type(within(dialog).getByLabelText("Justification"), "documented dummy key");
  expect(record).toBeEnabled();
  expect(confirm).toBeDisabled();

  // The server refuses a waiver that has already lapsed, so the control refuses it first.
  const expiry = within(dialog).getByLabelText("Expires on");
  await user.clear(expiry);
  await user.type(expiry, "2020-01-01");
  expect(record).toBeDisabled();
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test } from "vitest";
import { AuditSinksPage } from "./audit-sinks";

/**
 * The session is seeded rather than fetched, so an assertion that a control is absent cannot pass
 * merely because the roles have not arrived yet.
 */
function renderPage({ admin = true } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(["me"], {
    username: "alice",
    roles: [{ role: admin ? "admin" : "auditor", source: "config" }],
    claimsTruncated: false,
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuditSinksPage />
    </QueryClientProvider>,
  );
}

async function openAdd(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "New sink" }));
}

test("sink_positions_are_listed", async () => {
  renderPage();
  // The sink row carries its target and its position in the ledger.
  expect(await screen.findByRole("row", { name: /siem.*42.*3 entries/ })).toBeInTheDocument();
});

test("add_sink_is_disabled_until_the_name_and_url_are_valid", async () => {
  const user = userEvent.setup();
  renderPage();
  await openAdd(user);
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
  await openAdd(user);
  await user.type(await screen.findByLabelText("Sink name"), "new-siem");
  await user.type(screen.getByLabelText("Target URL"), "https://siem.example.com/ingest");
  await user.click(screen.getByRole("button", { name: "Add sink" }));
  expect(await screen.findByTestId("sink-secret")).toHaveTextContent("whsec_sink_shown_once");
  // The add form closed behind it: one dialog at a time.
  expect(screen.queryByLabelText("Sink name")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Done" }));
  expect(screen.queryByTestId("sink-secret")).not.toBeInTheDocument();
});

test("a_session_without_the_administrative_role_reads_the_sinks_but_is_offered_no_change", async () => {
  renderPage({ admin: false });
  expect(await screen.findByRole("row", { name: /siem/ })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "New sink" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /Delete sink/ })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /Replay sink/ })).not.toBeInTheDocument();
});

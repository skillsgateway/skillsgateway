import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test } from "vitest";
import { AuditPage } from "./audit";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuditPage />
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

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test } from "vitest";
import { autoApprovalOff } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { AutoApproval } from "./auto-approval";

const MARKETPLACE = "corp-marketplace";

function inThirtyDays(): string {
  const date = new Date();
  date.setDate(date.getDate() + 30);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function renderCard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AutoApproval marketplace={MARKETPLACE} canAdminister />
    </QueryClientProvider>,
  );
}

test("automatic_approval_is_turned_on_only_with_a_reason_that_is_not_blank_and_a_date", async () => {
  const user = userEvent.setup();
  renderCard();
  await user.click(await screen.findByRole("button", { name: "On" }));
  const save = screen.getByRole("button", { name: "Save automatic approval" });
  expect(save).toBeDisabled();
  expect(save.closest("form")).toHaveTextContent(/A reason is required, and a date from tomorrow/);

  await user.type(screen.getByLabelText("Reason"), "   ");
  fireEvent.change(screen.getByLabelText("Lapses on"), { target: { value: inThirtyDays() } });
  expect(save).toBeDisabled();

  await user.type(screen.getByLabelText("Reason"), "our own skills");
  expect(save).toBeEnabled();
});

test("automatic_approval_that_failed_to_load_can_be_retried", async () => {
  let calls = 0;
  server.use(
    http.get("/api/v1/marketplaces/:name/auto-approval", () => {
      calls += 1;
      return calls === 1
        ? HttpResponse.json({ detail: "the gateway is restarting" }, { status: 503 })
        : HttpResponse.json(autoApprovalOff);
    }),
  );
  const user = userEvent.setup();
  renderCard();
  expect(await screen.findByRole("alert")).toHaveTextContent("the gateway is restarting");
  await user.click(screen.getByRole("button", { name: "Try again" }));
  expect(await screen.findByRole("button", { name: "Save automatic approval" })).toBeEnabled();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

test("a_refused_revocation_shows_the_servers_next_step", async () => {
  server.use(
    http.get("/api/v1/marketplaces/:name/auto-approval", () =>
      HttpResponse.json({
        ...autoApprovalOff,
        enabled: true,
        allowedBy: "exemption",
        mode: "on",
        exemption: { id: 1, reason: "vendor", createdBy: "alice", expiresAt: "2099-01-01T00:00:00Z" },
      }),
    ),
    http.delete("/api/v1/marketplaces/:name/auto-approval/exemption", () =>
      HttpResponse.json(
        { detail: "the exemption changed since this page loaded", nextStep: "reload the page and try again" },
        { status: 409 },
      ),
    ),
  );
  const user = userEvent.setup();
  renderCard();
  await user.click(await screen.findByRole("button", { name: "Revoke exemption…" }));
  await user.click(screen.getByRole("button", { name: "Revoke exemption" }));
  const alert = await screen.findByRole("alert");
  expect(alert).toHaveTextContent("the exemption changed since this page loaded");
  expect(alert).toHaveTextContent("Next step: reload the page and try again");
});

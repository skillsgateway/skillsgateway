import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test } from "vitest";
import { manualPosture } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { ApprovalPosture } from "./approval-posture";

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
      <ApprovalPosture marketplace={MARKETPLACE} canAdminister />
    </QueryClientProvider>,
  );
}

test("an_automatic_posture_is_saved_only_with_a_reason_that_is_not_blank_and_a_date", async () => {
  const user = userEvent.setup();
  renderCard();
  await user.click(await screen.findByRole("button", { name: "Automatic" }));
  const save = screen.getByRole("button", { name: "Save posture" });
  expect(save).toBeDisabled();
  expect(save.closest("form")).toHaveTextContent(/A reason is required, and a date from tomorrow/);

  await user.type(screen.getByLabelText("Reason"), "   ");
  fireEvent.change(screen.getByLabelText("Lapses on"), { target: { value: inThirtyDays() } });
  expect(save).toBeDisabled();

  await user.type(screen.getByLabelText("Reason"), "our own skills");
  expect(save).toBeEnabled();
});

test("a_posture_that_failed_to_load_can_be_retried", async () => {
  let calls = 0;
  server.use(
    http.get("/api/v1/marketplaces/:name/approval-posture", () => {
      calls += 1;
      return calls === 1
        ? HttpResponse.json({ detail: "the gateway is restarting" }, { status: 503 })
        : HttpResponse.json(manualPosture);
    }),
  );
  const user = userEvent.setup();
  renderCard();
  expect(await screen.findByRole("alert")).toHaveTextContent("the gateway is restarting");
  await user.click(screen.getByRole("button", { name: "Try again" }));
  expect(await screen.findByRole("button", { name: "Save posture" })).toBeEnabled();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

test("a_refused_revocation_shows_the_servers_next_step", async () => {
  server.use(
    http.get("/api/v1/marketplaces/:name/approval-posture", () =>
      HttpResponse.json({
        ...manualPosture,
        posture: "auto",
        basis: "override",
        mode: "on",
        override: { id: 1, reason: "vendor", createdBy: "alice", expiresAt: "2099-01-01T00:00:00Z" },
      }),
    ),
    http.delete("/api/v1/marketplaces/:name/approval-posture/override", () =>
      HttpResponse.json(
        { detail: "the override changed since this page loaded", nextStep: "reload the page and try again" },
        { status: 409 },
      ),
    ),
  );
  const user = userEvent.setup();
  renderCard();
  await user.click(await screen.findByRole("button", { name: "Revoke override…" }));
  await user.click(screen.getByRole("button", { name: "Revoke override" }));
  const alert = await screen.findByRole("alert");
  expect(alert).toHaveTextContent("the override changed since this page loaded");
  expect(alert).toHaveTextContent("Next step: reload the page and try again");
});

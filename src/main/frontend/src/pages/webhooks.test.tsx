import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test } from "vitest";
import { WebhooksPage } from "./webhooks";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <WebhooksPage />
    </QueryClientProvider>,
  );
}

test("subscribers_and_their_delivery_attempts_are_listed", async () => {
  renderPage();
  // The subscriber row carries its target URL and event filter.
  expect(await screen.findByRole("row", { name: /ci-bot.*snapshot\.approved.*enabled/ })).toBeInTheDocument();
  // The delivery row carries the event, its subscriber, state, and attempt count.
  expect(await screen.findByRole("row", { name: /snapshot\.approved ci-bot delivered 1/ })).toBeInTheDocument();
});

test("created_subscriber_secret_is_shown_once_in_a_dialog", async () => {
  const user = userEvent.setup();
  renderPage();
  await user.type(await screen.findByLabelText("Subscriber name"), "new-bot");
  await user.type(screen.getByLabelText("Target URL"), "https://ci.example.com/hooks/skills-gateway");
  await user.click(screen.getByRole("button", { name: "Add subscriber" }));
  expect(await screen.findByTestId("webhook-secret")).toHaveTextContent("whsec_shown_once");
  await user.click(screen.getByRole("button", { name: "Done" }));
  expect(screen.queryByTestId("webhook-secret")).not.toBeInTheDocument();
});

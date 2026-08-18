import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test } from "vitest";
import { server } from "@/test/msw-server";
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

test("add_subscriber_is_disabled_until_the_name_and_url_are_valid", async () => {
  const user = userEvent.setup();
  renderPage();
  const nameField = await screen.findByLabelText("Subscriber name");
  const urlField = screen.getByLabelText("Target URL");
  const addButton = screen.getByRole("button", { name: "Add subscriber" });

  expect(addButton).toBeDisabled();

  // Whitespace is not a name, and a name alone is not a subscriber.
  await user.type(nameField, "   ");
  expect(addButton).toBeDisabled();
  await user.clear(nameField);
  await user.type(nameField, "new-bot");
  expect(addButton).toBeDisabled();

  // A URL without a scheme is what the server refuses; the client refuses it first.
  await user.type(urlField, "ci.example.com/hooks");
  expect(addButton).toBeDisabled();

  // A name the gateway's name pattern rejects keeps the control disabled too.
  await user.clear(urlField);
  await user.type(urlField, "https://ci.example.com/hooks/skills-gateway");
  await user.clear(nameField);
  await user.type(nameField, "Bad Name");
  expect(addButton).toBeDisabled();

  await user.clear(nameField);
  await user.type(nameField, "new-bot");
  expect(addButton).toBeEnabled();
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

/**
 * The filter is composed from the server's registry, so the wire value is what matters: every
 * event ticked has to submit the wildcard rather than an enumeration, or a subscriber silently
 * stops receiving events added to the gateway after it was registered.
 */
test("every_event_selected_submits_the_wildcard_filter", async () => {
  const user = userEvent.setup();
  let submitted: unknown = null;
  server.use(
    http.post("/api/webhooks", async ({ request }) => {
      submitted = await request.json();
      return HttpResponse.json({ id: 9, name: "x", url: "https://x.test", events: "*", secret: "s" }, { status: 201 });
    }),
  );
  renderPage();

  // Every event is ticked by default, matching the wildcard the old free-text field defaulted to.
  await waitFor(() =>
    expect(screen.getByRole("checkbox", { name: "All events" })).toBeChecked(),
  );
  await user.type(screen.getByLabelText("Subscriber name"), "ci-bot");
  await user.type(screen.getByLabelText("Target URL"), "https://ci.example.com/hooks");
  await user.click(screen.getByRole("button", { name: "Add subscriber" }));

  await waitFor(() => expect(submitted).toEqual({
    name: "ci-bot",
    url: "https://ci.example.com/hooks",
    events: "*",
  }));
});

test("a_partial_selection_submits_the_comma_delimited_names", async () => {
  const user = userEvent.setup();
  let submitted: unknown = null;
  server.use(
    http.post("/api/webhooks", async ({ request }) => {
      submitted = await request.json();
      return HttpResponse.json({ id: 9, name: "x", url: "https://x.test", events: "x", secret: "s" }, { status: 201 });
    }),
  );
  renderPage();

  await waitFor(() =>
    expect(screen.getByRole("checkbox", { name: "All events" })).toBeChecked(),
  );
  await user.click(screen.getByRole("checkbox", { name: "All events" }));
  await user.click(screen.getByRole("checkbox", { name: "snapshot.approved" }));
  await user.click(screen.getByRole("checkbox", { name: "snapshot.revoked" }));
  await user.type(screen.getByLabelText("Subscriber name"), "ci-bot");
  await user.type(screen.getByLabelText("Target URL"), "https://ci.example.com/hooks");
  await user.click(screen.getByRole("button", { name: "Add subscriber" }));

  await waitFor(() =>
    expect(submitted).toEqual({
      name: "ci-bot",
      url: "https://ci.example.com/hooks",
      events: "snapshot.approved,snapshot.revoked",
    }),
  );
});

test("add_subscriber_is_disabled_when_no_event_is_selected", async () => {
  const user = userEvent.setup();
  renderPage();
  await waitFor(() =>
    expect(screen.getByRole("checkbox", { name: "All events" })).toBeChecked(),
  );
  await user.type(screen.getByLabelText("Subscriber name"), "ci-bot");
  await user.type(screen.getByLabelText("Target URL"), "https://ci.example.com/hooks");
  expect(screen.getByRole("button", { name: "Add subscriber" })).toBeEnabled();

  // Clearing the selection leaves a filter that matches nothing, so it must not be submittable.
  await user.click(screen.getByRole("checkbox", { name: "All events" }));
  expect(screen.getByRole("button", { name: "Add subscriber" })).toBeDisabled();
});

test("typing_narrows_the_offered_events_without_changing_the_selection", async () => {
  const user = userEvent.setup();
  renderPage();
  expect(await screen.findByRole("checkbox", { name: "snapshot.revoked" })).toBeInTheDocument();

  await user.type(screen.getByLabelText("Events"), "approved");

  expect(screen.getByRole("checkbox", { name: "snapshot.approved" })).toBeInTheDocument();
  expect(screen.queryByRole("checkbox", { name: "snapshot.revoked" })).not.toBeInTheDocument();
  // Narrowing the view must not deselect what it hides.
  expect(screen.getByRole("checkbox", { name: "All events" })).toBeChecked();
});

test("a_stored_filter_naming_an_unknown_event_is_marked", async () => {
  server.use(
    http.get("/api/webhooks", () =>
      HttpResponse.json([
        { id: 1, name: "stale-bot", url: "https://stale.test/hook", events: "snapshot.aproved", enabled: true },
      ]),
    ),
  );
  renderPage();
  expect(await screen.findByText("unknown event")).toBeInTheDocument();
});

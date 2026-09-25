import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { delivery, subscriber } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { WebhooksPage } from "./webhooks";

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
      <MemoryRouter>
        <WebhooksPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function openAdd(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "New subscriber" }));
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
  await openAdd(user);
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
  await openAdd(user);
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
    http.post("/api/v1/webhooks", async ({ request }) => {
      submitted = await request.json();
      return HttpResponse.json({ id: 9, name: "x", url: "https://x.test", events: ["*"], secret: "s" }, { status: 201 });
    }),
  );
  renderPage();
  await openAdd(user);

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
    events: ["*"],
  }));
});

test("a_partial_selection_submits_the_selected_names", async () => {
  const user = userEvent.setup();
  let submitted: unknown = null;
  server.use(
    http.post("/api/v1/webhooks", async ({ request }) => {
      submitted = await request.json();
      return HttpResponse.json({ id: 9, name: "x", url: "https://x.test", events: ["x"], secret: "s" }, { status: 201 });
    }),
  );
  renderPage();
  await openAdd(user);

  await waitFor(() =>
    expect(screen.getByRole("checkbox", { name: "All events" })).toBeChecked(),
  );
  await user.click(screen.getByRole("checkbox", { name: "All events" }));
  await user.click(screen.getByRole("checkbox", { name: "marketplace.snapshot.approved" }));
  await user.click(screen.getByRole("checkbox", { name: "marketplace.snapshot.revoked" }));
  await user.type(screen.getByLabelText("Subscriber name"), "ci-bot");
  await user.type(screen.getByLabelText("Target URL"), "https://ci.example.com/hooks");
  await user.click(screen.getByRole("button", { name: "Add subscriber" }));

  await waitFor(() =>
    expect(submitted).toEqual({
      name: "ci-bot",
      url: "https://ci.example.com/hooks",
      events: ["marketplace.snapshot.approved", "marketplace.snapshot.revoked"],
    }),
  );
});

test("add_subscriber_is_disabled_when_no_event_is_selected", async () => {
  const user = userEvent.setup();
  renderPage();
  await openAdd(user);
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
  await openAdd(user);
  expect(await screen.findByRole("checkbox", { name: "marketplace.snapshot.revoked" })).toBeInTheDocument();

  await user.type(screen.getByLabelText("Events"), "approved");

  expect(screen.getByRole("checkbox", { name: "marketplace.snapshot.approved" })).toBeInTheDocument();
  expect(screen.queryByRole("checkbox", { name: "marketplace.snapshot.revoked" })).not.toBeInTheDocument();
  // Narrowing the view must not deselect what it hides.
  expect(screen.getByRole("checkbox", { name: "All events" })).toBeChecked();
});

test("a_stored_filter_naming_an_unknown_event_is_marked", async () => {
  server.use(
    http.get("/api/v1/webhooks", () =>
      HttpResponse.json([
        { id: 1, name: "stale-bot", url: "https://stale.test/hook", events: ["marketplace.snapshot.aproved"], enabled: true },
      ]),
    ),
  );
  renderPage();
  expect(await screen.findByText("unknown event")).toBeInTheDocument();
});

test("a_session_without_the_administrative_role_reads_the_subscribers_but_is_offered_no_change", async () => {
  renderPage({ admin: false });
  expect(await screen.findByRole("row", { name: /ci-bot.*snapshot\.approved.*enabled/ })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "New subscriber" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /Delete subscriber/ })).not.toBeInTheDocument();
});

/**
 * An audit sink delivers through a webhook subscriber. Listed as one, it read as a stale subscriber
 * with an unknown event, and deleting it took the sink with it; its deliveries still belong here.
 *
 * @SVCs SVC_GW_WEBHOOK_0004
 */
test("a_sinks_channel_is_not_listed_as_a_subscriber_and_its_deliveries_lead_to_the_sink", async () => {
  server.use(
    http.get("/api/v1/webhooks", () =>
      HttpResponse.json([
        subscriber,
        {
          id: 7,
          name: "siem",
          url: "https://siem.example.com/ingest",
          events: ["audit.export"],
          enabled: true,
          createdAt: "2026-08-14T10:00:00Z",
          auditSink: "siem",
        },
      ]),
    ),
    http.get("/api/v1/webhooks/deliveries", () =>
      HttpResponse.json([
        delivery,
        { ...delivery, id: 12, subscriberId: 7, event: "audit.export", payload: "{}" },
      ]),
    ),
  );
  renderPage();

  expect(await screen.findByRole("row", { name: /ci-bot.*snapshot\.approved.*enabled/ })).toBeInTheDocument();
  expect(screen.queryByRole("row", { name: /https:\/\/siem\.example\.com/ })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Delete subscriber siem" })).not.toBeInTheDocument();

  const sinkDelivery = await screen.findByRole("row", { name: /audit\.export/ });
  expect(within(sinkDelivery).getByRole("link", { name: "siem · audit sink" })).toHaveAttribute(
    "href",
    "/integrations/sinks",
  );
});

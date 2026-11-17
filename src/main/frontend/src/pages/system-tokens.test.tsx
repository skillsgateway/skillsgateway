import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, test } from "vitest";
import { server } from "@/test/msw-server";
import { SystemTokensPage } from "./system-tokens";

type Role = { role: string; marketplace?: string; source: string };
const ADMIN: Role[] = [{ role: "admin", source: "config" }];

function signedInAs(roles: Role[]) {
  server.use(
    http.get("/api/v1/me", () => HttpResponse.json({ username: "ada", roles, claimsTruncated: false })),
  );
}

function renderPage(queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <SystemTokensPage />
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

afterEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
});

/**
 * Only an administrator is shown the page, and nobody else's session makes a read behind it.
 *
 * @SVCs SVC_GW_AUTH_0087
 */
test("a_session_without_the_administrator_role_gets_the_role_statement_and_makes_no_machine_token_read", async () => {
  const reads: string[] = [];
  const listener = ({ request }: { request: Request }) => {
    const path = new URL(request.url).pathname;
    if (path.startsWith("/api/v1/tokens/machine")) reads.push(path);
  };
  server.events.on("request:start", listener);
  const refused: { name: string; roles: Role[] }[] = [
    { name: "no role", roles: [] },
    { name: "auditor", roles: [{ role: "auditor", source: "grant" }] },
    { name: "approver", roles: [{ role: "approver", marketplace: "corp-marketplace", source: "grant" }] },
  ];
  for (const { roles } of refused) {
    signedInAs(roles);
    const view = renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("This page needs the administrative role.");
    expect(screen.queryByRole("button", { name: "Create token" })).not.toBeInTheDocument();
    expect(screen.queryByText("platform-pipeline")).not.toBeInTheDocument();
    view.unmount();
  }
  server.events.removeListener("request:start", listener);
  expect(reads).toEqual([]);

  signedInAs(ADMIN);
  renderPage();
  expect(await screen.findByRole("heading", { name: "System access tokens" })).toBeInTheDocument();
  expect(await screen.findByText("platform-pipeline")).toBeInTheDocument();
});

test("the_list_shows_who_owns_what_and_never_a_secret", async () => {
  signedInAs(ADMIN);
  renderPage();
  const row = await screen.findByRole("row", { name: /platform-pipeline/ });
  expect(within(row).getByText("terraform-ci")).toBeInTheDocument();
  expect(within(row).getByText("ada")).toBeInTheDocument();
  expect(within(row).getByText("estate:read")).toBeInTheDocument();
  expect(document.body.textContent).not.toContain("sgw_");
});

test("the_two_kinds_of_token_are_told_apart_and_each_page_links_to_the_other", async () => {
  signedInAs(ADMIN);
  renderPage();
  expect(await screen.findByText(/machine API credentials/)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "personal access token" })).toHaveAttribute("href", "/security/tokens");
});

/**
 * @SVCs SVC_GW_AUTH_0088
 */
test("create_needs_a_chosen_lifetime_offers_the_registrys_scopes_and_no_all", async () => {
  signedInAs(ADMIN);
  const posted: Record<string, unknown>[] = [];
  server.use(
    http.post("/api/v1/tokens/machine", async ({ request }) => {
      posted.push((await request.json()) as Record<string, unknown>);
      return HttpResponse.json({ id: 30, name: "p", token: "sgw_system_x", createdAt: "2026-08-14T10:00:00Z" }, { status: 201 });
    }),
  );
  const user = userEvent.setup();
  renderPage();
  const create = await screen.findByRole("button", { name: "Create token" });

  const expires = screen.getByRole("group", { name: "Expires" });
  expect(within(expires).getAllByRole("button").map((b) => b.textContent)).toEqual([
    "30 days",
    "90 days",
    "180 days",
    "1 year",
    "Never",
  ]);
  // Nothing is preselected: the lifetime of an unattended credential is chosen, not defaulted.
  for (const button of within(expires).getAllByRole("button")) {
    expect(button).toHaveAttribute("aria-pressed", "false");
  }
  const scopeBoxes = await screen.findAllByRole("checkbox");
  expect(scopeBoxes.map((box) => box.getAttribute("aria-label"))).toEqual([
    "estate:read",
    "marketplaces:read",
    "marketplaces:register",
  ]);
  expect(screen.queryByRole("checkbox", { name: /^(all|\*)$/i })).not.toBeInTheDocument();
  // What a scope reaches is available to a keyboard and a screen reader, not only on hover.
  expect(screen.getByRole("checkbox", { name: "estate:read" })).toHaveAccessibleDescription(
    "Reaches GET /api/v1/estate",
  );

  await user.type(screen.getByLabelText("Principal"), "terraform-ci");
  await user.type(screen.getByLabelText("Token name"), "platform-pipeline");
  await user.click(screen.getByRole("checkbox", { name: "estate:read" }));
  expect(create).toBeDisabled(); // no lifetime yet
  await user.click(within(expires).getByRole("button", { name: "1 year" }));
  expect(create).toBeEnabled();
  const before = Date.now();
  await user.click(create);
  await screen.findByTestId("token-cleartext");

  expect(posted).toHaveLength(1);
  expect(posted[0]).toMatchObject({ principal: "terraform-ci", name: "platform-pipeline", apiScopes: ["estate:read"] });
  expect(posted[0]).not.toHaveProperty("neverExpires");
  const ahead = Date.parse(String(posted[0]!.expiresAt)) - before;
  expect(ahead).toBeGreaterThan(365 * 86_400_000 - 60_000);
  expect(ahead).toBeLessThan(365 * 86_400_000 + 60_000);
});

test("never_is_sent_as_the_explicit_choice_and_not_as_a_missing_expiry", async () => {
  signedInAs(ADMIN);
  const posted: Record<string, unknown>[] = [];
  server.use(
    http.post("/api/v1/tokens/machine", async ({ request }) => {
      posted.push((await request.json()) as Record<string, unknown>);
      return HttpResponse.json({ id: 31, name: "p", token: "sgw_system_y", createdAt: "2026-08-14T10:00:00Z" }, { status: 201 });
    }),
  );
  const user = userEvent.setup();
  renderPage();
  await user.type(await screen.findByLabelText("Principal"), "sync");
  await user.type(screen.getByLabelText("Token name"), "forever");
  await user.click(await screen.findByRole("checkbox", { name: "marketplaces:read" }));
  await user.click(within(screen.getByRole("group", { name: "Expires" })).getByRole("button", { name: "Never" }));
  await user.click(screen.getByRole("button", { name: "Create token" }));
  await screen.findByTestId("token-cleartext");
  expect(posted[0]).toMatchObject({ neverExpires: true });
  expect(posted[0]).not.toHaveProperty("expiresAt");
});

test("the_apis_refusal_is_shown_as_it_states_it", async () => {
  signedInAs(ADMIN);
  server.use(
    http.post("/api/v1/tokens/machine", () =>
      HttpResponse.json(
        { title: "Unprocessable Entity", status: 422, detail: "this gateway caps token lifetime at PT720H, so a machine credential that never expires is refused" },
        { status: 422, headers: { "Content-Type": "application/problem+json" } },
      ),
    ),
  );
  const user = userEvent.setup();
  renderPage();
  await user.type(await screen.findByLabelText("Principal"), "sync");
  await user.type(screen.getByLabelText("Token name"), "forever");
  await user.click(await screen.findByRole("checkbox", { name: "estate:read" }));
  await user.click(within(screen.getByRole("group", { name: "Expires" })).getByRole("button", { name: "Never" }));
  await user.click(screen.getByRole("button", { name: "Create token" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(/caps token lifetime/);
  expect(screen.queryByTestId("token-cleartext")).not.toBeInTheDocument();
});

test("rotate_and_revoke_each_ask_first_and_reach_their_endpoint_only_when_confirmed", async () => {
  signedInAs(ADMIN);
  const calls: string[] = [];
  server.use(
    http.post("/api/v1/tokens/machine/:id/rotate", ({ params }) => {
      calls.push(`rotate ${String(params.id)}`);
      return HttpResponse.json({ id: 99, name: "platform-pipeline", token: "sgw_rotated_secret", createdAt: "2026-08-14T10:00:00Z" });
    }),
    http.delete("/api/v1/tokens/machine/:id", ({ params }) => {
      calls.push(`revoke ${String(params.id)}`);
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const user = userEvent.setup();
  const { queryClient } = renderPage();
  const row = await screen.findByRole("row", { name: /platform-pipeline/ });

  await user.click(within(row).getByRole("button", { name: "Rotate token platform-pipeline" }));
  expect(calls).toEqual([]);
  await user.click(screen.getByRole("button", { name: "Cancel" }));
  expect(calls).toEqual([]);

  await user.click(within(row).getByRole("button", { name: "Rotate token platform-pipeline" }));
  await user.click(screen.getByRole("button", { name: "Rotate token" }));
  expect(await screen.findByTestId("token-cleartext")).toHaveTextContent("sgw_rotated_secret");
  expect(calls).toEqual(["rotate 21"]);
  await user.click(screen.getByRole("button", { name: "Done" }));
  // A rotation returns a cleartext too, and is held to the same rule as a creation.
  await waitFor(() => expect(queryClient.getMutationCache().getAll()).toEqual([]));
  expect(document.body.textContent).not.toContain("sgw_rotated_secret");

  await user.click(within(row).getByRole("button", { name: "Revoke token platform-pipeline" }));
  expect(calls).toEqual(["rotate 21"]);
  await user.click(screen.getByRole("button", { name: "Revoke token" }));
  await waitFor(() => expect(calls).toEqual(["rotate 21", "revoke 21"]));
});

/**
 * The cleartext is shown once and retained nowhere afterwards.
 *
 * @SVCs SVC_GW_AUTH_0089
 */
test("a_created_tokens_cleartext_is_gone_from_page_cache_storage_and_address_after_the_dialog_closes", async () => {
  signedInAs(ADMIN);
  const user = userEvent.setup();
  const { queryClient } = renderPage();
  await user.type(await screen.findByLabelText("Principal"), "terraform-ci");
  await user.type(screen.getByLabelText("Token name"), "platform-pipeline");
  await user.click(await screen.findByRole("checkbox", { name: "estate:read" }));
  await user.click(within(screen.getByRole("group", { name: "Expires" })).getByRole("button", { name: "90 days" }));
  await user.click(screen.getByRole("button", { name: "Create token" }));

  const secret = "sgw_system_cleartext_shown_once";
  expect(await screen.findByTestId("token-cleartext")).toHaveTextContent(secret);
  expect(screen.getByText(/cannot be shown again/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Copy token to clipboard" })).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Done" }));
  expect(screen.queryByTestId("token-cleartext")).not.toBeInTheDocument();
  expect(document.body.textContent).not.toContain(secret);
  // The mutation result is dropped with the dialog (gcTime 0) and the list is refetched, never seeded.
  await waitFor(() => expect(queryClient.getMutationCache().getAll()).toEqual([]));
  expect(JSON.stringify(queryClient.getQueryCache().getAll().map((q) => q.state.data))).not.toContain(secret);
  expect(JSON.stringify({ ...window.localStorage })).not.toContain(secret);
  expect(JSON.stringify({ ...window.sessionStorage })).not.toContain(secret);
  expect(window.location.href).not.toContain(secret);
});

/**
 * @SVCs SVC_GW_AUTH_0090
 */
test("credentials_near_expiry_or_never_used_are_marked_in_words", async () => {
  signedInAs(ADMIN);
  renderPage();
  const forgotten = await screen.findByRole("row", { name: /old-job/ });
  expect(within(forgotten).getByText("never used")).toBeInTheDocument();
  // One statement, not "never" over "never used".
  expect(within(forgotten).queryByText("never")).not.toBeInTheDocument();
  const forever = screen.getByRole("row", { name: /forever/ });
  expect(within(forever).getByText("never expires")).toBeInTheDocument();
  // The riskiest state in the list is not greyed out like an absent value.
  expect(within(forever).getByText("never expires")).not.toHaveClass("text-muted-foreground");
  expect(within(forever).queryByText("never used")).not.toBeInTheDocument();
  const busy = screen.getByRole("row", { name: /platform-pipeline/ });
  expect(within(busy).queryByText(/never used|expires within/)).not.toBeInTheDocument();
});

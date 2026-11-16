import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { manyMarketplaces, marketplace, scopedTokenViews } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { TokensPage } from "./tokens";

/** The client is returned so a test can look in the cache a mounted page leaves behind. */
let queryClient: QueryClient;

function renderPage() {
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TokensPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

test("create_token_is_disabled_until_the_name_is_non_blank", async () => {
  const user = userEvent.setup();
  renderPage();
  const nameField = await screen.findByLabelText("Token name");
  const createButton = screen.getByRole("button", { name: "Create token" });

  expect(createButton).toBeDisabled();

  await user.type(nameField, "   ");
  expect(createButton).toBeDisabled();

  await user.clear(nameField);
  await user.type(nameField, "ci-runner");
  expect(createButton).toBeEnabled();
});

test("created_token_cleartext_is_shown_once_in_a_dialog", async () => {
  const user = userEvent.setup();
  renderPage();
  await user.type(await screen.findByLabelText("Token name"), "ci-runner");
  await user.click(screen.getByRole("button", { name: "Create token" }));
  expect(await screen.findByTestId("token-cleartext")).toHaveTextContent("sgw_cleartext_shown_once");
  await user.click(screen.getByRole("button", { name: "Done" }));
  expect(screen.queryByTestId("token-cleartext")).not.toBeInTheDocument();
});

test("the_last_used_column_shows_recency_with_the_exact_instant_a_hover_away", async () => {
  renderPage();
  const used = await screen.findByRole("row", { name: /ci-runner/ });
  // Relative for the reading, exact for the record: the wire value survives in both the
  // machine-readable attribute and the tooltip, whatever the visible text says.
  const stamp = within(used).getByTitle(/2026-08-14T13:00:00Z/);
  expect(stamp).toHaveAttribute("datetime", "2026-08-14T13:00:00Z");
  expect(stamp.textContent).not.toContain("2026-08-14T13:00:00Z");
});

// "never" rather than an em dash: a credential nothing has authenticated with is the answer an
// operator deciding whether to revoke it is looking for.
test("a_token_that_has_never_authenticated_says_never", async () => {
  renderPage();
  const unused = await screen.findByRole("row", { name: /spare-laptop/ });
  expect(within(unused).getByText("never")).toBeInTheDocument();
});

/**
 * The persona a role-less session is: tokens are scoped per principal server-side, so this
 * page is the whole of what it can do here. An empty list is a stated state, and the create
 * form is still offered — nothing about the page is gated on holding a role.
 */
test("a_session_with_no_role_sees_an_empty_token_list_and_can_still_create_one", async () => {
  server.use(http.get("/api/v1/tokens", () => HttpResponse.json([])));
  renderPage();
  expect(await screen.findByText("No tokens yet.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Create token" })).toBeInTheDocument();
});

/** A refused or failed read is an error the reader can see, not an empty list. */
test("a_failed_token_read_renders_the_servers_reason", async () => {
  server.use(
    http.get("/api/v1/tokens", () =>
      HttpResponse.json({ detail: "Token store unavailable" }, { status: 503 }),
    ),
  );
  renderPage();
  expect(await screen.findByRole("alert")).toHaveTextContent("Token store unavailable");
  expect(screen.queryByText("No tokens yet.")).not.toBeInTheDocument();
});

/**
 * The page offers the wizard's lifetimes with the wizard's default, posts the chosen one, and
 * lists every token's expiry — never, a date, or expired.
 *
 * @SVCs SVC_GW_AUTH_0084, SVC_GW_AUTH_0094
 */
test("tokens_are_created_with_a_chosen_lifetime_and_listed_with_their_expiry", async () => {
  server.use(
    http.get("/api/v1/tokens", () =>
      HttpResponse.json([
        { id: 1, name: "forever", createdAt: "2026-08-14T10:00:00Z", lastUsedAt: "2026-08-14T13:00:00Z" },
        { id: 2, name: "bounded", createdAt: "2026-08-14T10:00:00Z", lastUsedAt: "2026-08-14T13:00:00Z", expiresAt: "2099-09-13T10:00:00Z" },
        { id: 3, name: "lapsed", createdAt: "2026-08-14T10:00:00Z", lastUsedAt: "2026-08-14T13:00:00Z", expiresAt: "2026-08-15T10:00:00Z" },
      ]),
    ),
  );
  const posted: { name?: string; expiresAt?: string }[] = [];
  server.use(
    http.post("/api/v1/tokens", async ({ request }) => {
      posted.push((await request.json()) as { name?: string; expiresAt?: string });
      return HttpResponse.json({ id: 9, name: "ci-runner", token: "sgw_x", createdAt: "2026-08-14T10:00:00Z" }, { status: 201 });
    }),
  );
  const user = userEvent.setup();
  renderPage();

  expect(await screen.findByRole("columnheader", { name: "Expires" })).toBeInTheDocument();
  expect(within(screen.getByRole("row", { name: /forever/ })).getByText("never")).toBeInTheDocument();
  expect(within(screen.getByRole("row", { name: /bounded/ })).getByTitle(/2099-09-13T10:00:00Z/)).toBeInTheDocument();
  expect(within(screen.getByRole("row", { name: /bounded/ })).getByText("active")).toBeInTheDocument();
  expect(within(screen.getByRole("row", { name: /lapsed/ })).getByText("expired")).toBeInTheDocument();

  const expires = screen.getByRole("group", { name: "Expires" });
  expect(within(expires).getByRole("button", { name: "30 days" })).toHaveAttribute("aria-pressed", "true");
  for (const label of ["90 days", "180 days", "1 year"]) {
    expect(within(expires).getByRole("button", { name: label })).toHaveAttribute("aria-pressed", "false");
  }
  // GW_AUTH_0078: exactly these four, and no way to choose a token that never expires.
  expect(within(expires).getAllByRole("button").map((button) => button.textContent)).toEqual([
    "30 days",
    "90 days",
    "180 days",
    "1 year",
  ]);

  await user.click(within(expires).getByRole("button", { name: "180 days" }));
  await user.type(screen.getByLabelText("Token name"), "ci-runner");
  const before = Date.now();
  await user.click(screen.getByRole("button", { name: "Create token" }));
  await screen.findByTestId("token-cleartext");

  expect(posted).toHaveLength(1);
  expect(posted[0]!.name).toBe("ci-runner");
  const ahead = Date.parse(posted[0]!.expiresAt ?? "") - before;
  expect(ahead).toBeGreaterThan(180 * 86_400_000 - 60_000);
  expect(ahead).toBeLessThan(180 * 86_400_000 + 60_000);
});

/** A refused lifetime is stated beside the choice, and choosing another clears it. */
test("a_refused_lifetime_is_stated_beside_the_choice_until_another_is_chosen", async () => {
  server.use(
    http.post("/api/v1/tokens", () =>
      HttpResponse.json({ detail: "expiresAt exceeds the maximum token lifetime" }, { status: 422 }),
    ),
  );
  const user = userEvent.setup();
  renderPage();
  await user.click(screen.getByRole("button", { name: "90 days" }));
  await user.type(screen.getByLabelText("Token name"), "ci-runner");
  await user.click(screen.getByRole("button", { name: "Create token" }));
  const refusal = await screen.findByRole("alert");
  expect(refusal).toHaveTextContent("maximum token lifetime");

  await user.click(screen.getByRole("button", { name: "30 days" }));
  expect(screen.queryByText(/maximum token lifetime/)).not.toBeInTheDocument();
});

/**
 * The two kinds of token are told apart: an administrator is pointed at the system tokens page, a
 * session without the role is not told about a page it cannot open.
 *
 * @SVCs SVC_GW_AUTH_0087
 */
test("only_an_administrator_is_pointed_at_system_access_tokens", async () => {
  server.use(
    http.get("/api/v1/me", () =>
      HttpResponse.json({ username: "bob", roles: [], claimsTruncated: false }),
    ),
  );
  const plain = renderPage();
  expect(await screen.findByRole("heading", { name: "Personal access tokens" })).toBeInTheDocument();
  expect(screen.queryByRole("link", { name: "system access token" })).not.toBeInTheDocument();
  plain.unmount();

  server.use(
    http.get("/api/v1/me", () =>
      HttpResponse.json({ username: "ada", roles: [{ role: "admin", source: "config" }], claimsTruncated: false }),
    ),
  );
  renderPage();
  expect(await screen.findByRole("link", { name: "system access token" })).toHaveAttribute(
    "href",
    "/configuration/tokens",
  );
});

type Posted = { name?: string; scopes?: string[] };

function recordPosts(): Posted[] {
  const posted: Posted[] = [];
  server.use(
    http.post("/api/v1/tokens", async ({ request }) => {
      posted.push((await request.json()) as Posted);
      return HttpResponse.json(
        { id: 9, name: "ci-runner", token: "sgw_x", createdAt: "2026-08-14T10:00:00Z" },
        { status: 201 },
      );
    }),
  );
  return posted;
}

function threeMarketplaces() {
  server.use(
    http.get("/api/v1/marketplaces", () =>
      HttpResponse.json([
        { ...marketplace, id: 1, name: "tools" },
        { ...marketplace, id: 2, name: "corp-marketplace" },
        { ...marketplace, id: 3, name: "data" },
      ]),
    ),
  );
}

/**
 * The default asks for every marketplace and says nothing in `scopes`; the limited choice sends
 * exactly the names ticked (GW_AUTH_0079).
 *
 * @SVCs SVC_GW_AUTH_0095
 */
test("a_token_is_created_unlimited_by_default_and_limited_to_the_marketplaces_chosen", async () => {
  threeMarketplaces();
  const posted = recordPosts();
  const user = userEvent.setup();
  renderPage();

  const choice = await screen.findByRole("group", { name: "Marketplaces" });
  expect(within(choice).getByRole("button", { name: "Every marketplace" })).toHaveAttribute("aria-pressed", "true");
  await user.type(screen.getByLabelText("Token name"), "everything");
  await user.click(screen.getByRole("button", { name: "Create token" }));
  await screen.findByTestId("token-cleartext");
  expect(posted).toHaveLength(1);
  expect(posted[0]).not.toHaveProperty("scopes");
  await user.click(screen.getByRole("button", { name: "Done" }));

  await user.click(within(choice).getByRole("button", { name: "Chosen marketplaces" }));
  await user.click(await screen.findByRole("checkbox", { name: "tools" }));
  await user.click(screen.getByRole("checkbox", { name: "data" }));
  expect(screen.getByTestId("chosen-summary")).toHaveTextContent("2 chosen: data, tools.");
  await user.type(screen.getByLabelText("Token name"), "two-only");
  await user.click(screen.getByRole("button", { name: "Create token" }));
  await screen.findByTestId("token-cleartext");
  expect(posted).toHaveLength(2);
  expect(posted[1]!.scopes).toEqual(["data", "tools"]);
});

/**
 * A limited choice that names nothing would be sent as no scopes, which the API reads as every
 * marketplace: the opposite of what was chosen. It is not creatable, and the reason is on screen.
 *
 * @SVCs SVC_GW_AUTH_0096
 */
test("a_limited_choice_naming_nothing_cannot_be_submitted_and_says_why", async () => {
  threeMarketplaces();
  const posted = recordPosts();
  const user = userEvent.setup();
  renderPage();
  await user.type(await screen.findByLabelText("Token name"), "ci-runner");
  const create = screen.getByRole("button", { name: "Create token" });
  expect(create).toBeEnabled();

  await user.click(screen.getByRole("button", { name: "Chosen marketplaces" }));
  expect(create).toBeDisabled();
  expect(screen.getByText(/Choose at least one marketplace/)).toBeInTheDocument();
  expect(screen.getByTestId("chosen-summary")).toHaveTextContent("None chosen.");

  await user.click(screen.getByRole("checkbox", { name: "tools" }));
  expect(create).toBeEnabled();
  await user.click(screen.getByRole("checkbox", { name: "tools" }));
  expect(create).toBeDisabled();
  expect(posted).toHaveLength(0);

  // Going back to every marketplace is a choice too, and makes the form creatable again.
  await user.click(screen.getByRole("button", { name: "Every marketplace" }));
  expect(create).toBeEnabled();
});

/**
 * Many marketplaces: the filter narrows the list, what is ticked survives the filter hiding it, and
 * the catalog is offered by the name the gateway reports, not by a name the portal guessed.
 *
 * @SVCs SVC_GW_AUTH_0096
 */
test("the_list_can_be_filtered_and_the_catalog_is_offered_by_the_name_the_gateway_reports", async () => {
  server.use(
    http.get("/api/v1/marketplaces", () => HttpResponse.json(manyMarketplaces(120))),
    http.get("/api/v1/catalog", () => HttpResponse.json({ name: "gwcatalog", sha: "a".repeat(40) })),
  );
  const posted = recordPosts();
  const user = userEvent.setup();
  renderPage();
  await user.type(await screen.findByLabelText("Token name"), "ci-runner");
  await user.click(screen.getByRole("button", { name: "Chosen marketplaces" }));

  expect(await screen.findAllByRole("checkbox")).toHaveLength(121);
  expect(screen.getByRole("checkbox", { name: "gwcatalog" })).toBeInTheDocument();
  expect(screen.queryByRole("checkbox", { name: "catalog" })).not.toBeInTheDocument();
  expect(screen.getByText(/every served marketplace in one repository/)).toBeInTheDocument();

  await user.click(screen.getByRole("checkbox", { name: "market-007" }));
  await user.type(screen.getByLabelText("Filter marketplaces"), "market-11");
  expect(screen.getAllByRole("checkbox").map((box) => box.getAttribute("aria-label"))).toEqual([
    "market-110",
    "market-111",
    "market-112",
    "market-113",
    "market-114",
    "market-115",
    "market-116",
    "market-117",
    "market-118",
    "market-119",
  ]);
  // Hidden by the filter, still chosen, still in the summary.
  expect(screen.getByTestId("chosen-summary")).toHaveTextContent("1 chosen: market-007.");
  await user.type(screen.getByLabelText("Filter marketplaces"), "zzz");
  expect(screen.getByText("No marketplace matches 'market-11zzz'.")).toBeInTheDocument();

  await user.clear(screen.getByLabelText("Filter marketplaces"));
  await user.click(screen.getByRole("checkbox", { name: "gwcatalog" }));
  await user.click(screen.getByRole("button", { name: "Create token" }));
  await screen.findByTestId("token-cleartext");
  expect(posted[0]!.scopes).toEqual(["gwcatalog", "market-007"]);
});

/** No catalog (disabled, or nothing generated yet) is no entry, and no error on the page. */
test("no_catalog_entry_is_offered_while_the_gateway_serves_none", async () => {
  server.use(
    http.get("/api/v1/catalog", () => HttpResponse.json({ detail: "catalog is disabled" }, { status: 404 })),
  );
  const user = userEvent.setup();
  renderPage();
  await user.click(await screen.findByRole("button", { name: "Chosen marketplaces" }));
  expect(await screen.findByRole("checkbox", { name: "corp-marketplace" })).toBeInTheDocument();
  expect(screen.getAllByRole("checkbox")).toHaveLength(1);
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

/**
 * The API validates scopes against its registry; a refusal is shown as the API states it, beside
 * the choice, and the portal does not second-guess a name it has just read.
 *
 * @SVCs SVC_GW_AUTH_0096
 */
test("a_refused_scope_is_shown_as_the_api_states_it", async () => {
  threeMarketplaces();
  server.use(
    http.post("/api/v1/tokens", () =>
      HttpResponse.json(
        { detail: "unknown scope 'tools': scopes name registered marketplaces or the catalog" },
        { status: 422 },
      ),
    ),
  );
  const user = userEvent.setup();
  renderPage();
  await user.type(await screen.findByLabelText("Token name"), "ci-runner");
  await user.click(screen.getByRole("button", { name: "Chosen marketplaces" }));
  await user.click(await screen.findByRole("checkbox", { name: "tools" }));
  await user.click(screen.getByRole("button", { name: "Create token" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "unknown scope 'tools': scopes name registered marketplaces or the catalog",
  );
  // Nothing was shown as created.
  expect(screen.queryByTestId("token-cleartext")).not.toBeInTheDocument();
});

/** Nothing here needs a role: a session with none can browse, so it can choose, and it can only narrow. */
test("a_session_with_no_role_can_create_a_limited_token_for_marketplaces_it_can_browse", async () => {
  server.use(
    http.get("/api/v1/me", () => HttpResponse.json({ username: "bob", roles: [], claimsTruncated: false })),
  );
  threeMarketplaces();
  const posted = recordPosts();
  const user = userEvent.setup();
  renderPage();
  await user.type(await screen.findByLabelText("Token name"), "ci-runner");
  await user.click(screen.getByRole("button", { name: "Chosen marketplaces" }));
  await user.click(await screen.findByRole("checkbox", { name: "corp-marketplace" }));
  await user.click(screen.getByRole("button", { name: "Create token" }));
  await screen.findByTestId("token-cleartext");
  expect(posted[0]!.scopes).toEqual(["corp-marketplace"]);
});

/**
 * Reach is stated in words for every row: "every marketplace" is a sentence, not an empty cell; a
 * long list is shortened with the whole of it still readable.
 *
 * @SVCs SVC_GW_AUTH_0098
 */
test("the_list_states_each_tokens_reach_in_words", async () => {
  server.use(http.get("/api/v1/tokens", () => HttpResponse.json(scopedTokenViews)));
  renderPage();
  expect(await screen.findByRole("columnheader", { name: "Reach" })).toBeInTheDocument();

  const unscoped = screen.getByRole("row", { name: /unscoped-laptop/ });
  expect(within(unscoped).getByText("every marketplace")).toBeInTheDocument();

  const one = screen.getByRole("row", { name: /ci-corp-only/ });
  expect(within(one).getByText("corp-marketplace")).toBeInTheDocument();
  expect(within(one).queryByText("every marketplace")).not.toBeInTheDocument();

  const many = screen.getByRole("row", { name: /ci-many/ });
  // Three names are shown, in order; the other two are summarised yet still readable and on hover.
  expect(within(many).getByText("corp-marketplace")).toBeInTheDocument();
  expect(within(many).getByText("data")).toBeInTheDocument();
  expect(within(many).getByText("docs")).toBeInTheDocument();
  expect(within(many).getByText(/\+2 more/)).toHaveTextContent("+2 more: infra, tools");
  expect(within(many).getByTitle("corp-marketplace, data, docs, infra, tools")).toBeInTheDocument();
});

/**
 * The cleartext is in the dialog and nowhere else: not in the cache's record of the request that
 * made it, even while this page, and so its observer, stays mounted (GW_AUTH_0081).
 *
 * @SVCs SVC_GW_AUTH_0100
 */
test("the_created_cleartext_is_not_left_in_the_mutation_cache", async () => {
  const user = userEvent.setup();
  renderPage();
  await user.type(await screen.findByLabelText("Token name"), "ci-runner");
  await user.click(screen.getByRole("button", { name: "Create token" }));
  expect(await screen.findByTestId("token-cleartext")).toHaveTextContent("sgw_cleartext_shown_once");

  // Open dialog, page still mounted: the case that held it. Collection is scheduled, not
  // synchronous, so wait for it rather than assert on the instant.
  await waitFor(() => expect(queryClient.getMutationCache().getAll()).toEqual([]));
  expect(JSON.stringify(queryClient.getQueryCache().getAll().map((query) => query.state.data))).not.toContain(
    "sgw_cleartext_shown_once",
  );
  expect(screen.getByTestId("token-cleartext")).toHaveTextContent("sgw_cleartext_shown_once");

  await user.click(screen.getByRole("button", { name: "Done" }));
  expect(screen.queryByText(/sgw_cleartext_shown_once/)).not.toBeInTheDocument();
  expect(queryClient.getMutationCache().getAll()).toEqual([]);
});

/**
 * Hardening: a list longer than anyone reads is capped with a stated reason and the filter still
 * reaches every name; a failed read of the marketplaces is shown with a way to retry it.
 *
 * @SVCs SVC_GW_AUTH_0096
 */
test("a_very_long_list_is_capped_with_a_reason_and_a_failed_read_can_be_retried", async () => {
  server.use(http.get("/api/v1/marketplaces", () => HttpResponse.json(manyMarketplaces(250))));
  const user = userEvent.setup();
  renderPage();
  await user.click(await screen.findByRole("button", { name: "Chosen marketplaces" }));
  expect(await screen.findAllByRole("checkbox")).toHaveLength(200);
  expect(screen.getByText("Showing the first 200 of 251: type in the filter to narrow the list.")).toBeInTheDocument();
  await user.type(screen.getByLabelText("Filter marketplaces"), "market-250");
  expect(screen.getAllByRole("checkbox").map((box) => box.getAttribute("aria-label"))).toEqual(["market-250"]);
});

test("a_failed_marketplace_read_is_stated_and_can_be_retried", async () => {
  let attempts = 0;
  server.use(
    http.get("/api/v1/marketplaces", () => {
      attempts += 1;
      return attempts === 1
        ? HttpResponse.json({ detail: "marketplace store unavailable" }, { status: 503 })
        : HttpResponse.json([{ ...marketplace, id: 1, name: "tools" }]);
    }),
  );
  const user = userEvent.setup();
  renderPage();
  await user.click(await screen.findByRole("button", { name: "Chosen marketplaces" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("marketplace store unavailable");
  await user.click(screen.getByRole("button", { name: "Try again" }));
  expect(await screen.findByRole("checkbox", { name: "tools" })).toBeInTheDocument();
  expect(screen.queryByText("marketplace store unavailable")).not.toBeInTheDocument();
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test } from "vitest";
import { server } from "@/test/msw-server";
import { TokensPage } from "./tokens";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <TokensPage />
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
 * @SVCs SVC_GW_AUTH_0084
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
  for (const label of ["7 days", "90 days", "No expiry"]) {
    expect(within(expires).getByRole("button", { name: label })).toHaveAttribute("aria-pressed", "false");
  }

  await user.click(within(expires).getByRole("button", { name: "7 days" }));
  await user.type(screen.getByLabelText("Token name"), "ci-runner");
  const before = Date.now();
  await user.click(screen.getByRole("button", { name: "Create token" }));
  await screen.findByTestId("token-cleartext");

  expect(posted).toHaveLength(1);
  expect(posted[0]!.name).toBe("ci-runner");
  const ahead = Date.parse(posted[0]!.expiresAt ?? "") - before;
  expect(ahead).toBeGreaterThan(7 * 86_400_000 - 60_000);
  expect(ahead).toBeLessThan(7 * 86_400_000 + 60_000);
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

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

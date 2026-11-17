import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { marketplace, scopedTokenViews } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { TokensPage } from "./tokens";

/**
 * Editing a personal access token's reach from the list (GW_AUTH_0088): the control, its rules,
 * its refusals and what it leaves behind. Create-form behaviour is in tokens.test.tsx.
 */

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

type PatchBody = { scopes?: string[]; allMarketplaces?: boolean };
type Patched = { id: number; body: PatchBody };

/** A token list that remembers a PATCH, as the server would, and the bodies it was sent. */
function statefulTokens(initial: typeof scopedTokenViews): Patched[] {
  let tokens = initial.map((token) => ({ ...token }));
  const patched: Patched[] = [];
  server.use(
    http.get("/api/v1/tokens", () => HttpResponse.json(tokens)),
    http.patch("/api/v1/tokens/:id", async ({ request, params }) => {
      const body = (await request.json()) as PatchBody;
      const id = Number(params.id);
      patched.push({ id, body });
      // As the server stores it: every marketplace is an empty `scopes`, but only ever by statement.
      const scopes = body.allMarketplaces ? [] : body.scopes;
      tokens = tokens.map((token) => (token.id === id ? { ...token, scopes } : token));
      return HttpResponse.json(tokens.find((token) => token.id === id));
    }),
  );
  return patched;
}

async function openEdit(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole("button", { name: `Edit reach of token ${name}` }));
  return screen.findByRole("dialog", { name: `Edit reach of '${name}'` });
}

/**
 * The edit starts from the token's own reach, cannot be saved while it names nothing or changes
 * nothing, says why, and never sends an empty list for a limited choice.
 *
 * @SVCs SVC_GW_AUTH_0108
 */
test("editing_a_tokens_reach_is_disabled_until_valid_and_different_and_never_sends_an_empty_limited_list", async () => {
  threeMarketplaces();
  const patched = statefulTokens(scopedTokenViews);
  const user = userEvent.setup();
  renderPage();
  const dialog = await openEdit(user, "ci-corp-only");
  const save = within(dialog).getByRole("button", { name: "Save reach" });

  // Starts from the current reach: corp-marketplace chosen, nothing to save yet, and it says so.
  expect(within(dialog).getByRole("button", { name: "Chosen marketplaces" })).toHaveAttribute("aria-pressed", "true");
  expect(await within(dialog).findByRole("checkbox", { name: "corp-marketplace" })).toBeChecked();
  expect(save).toBeDisabled();
  expect(save).toHaveAccessibleDescription(/current reach/);

  // Naming nothing is not saveable, and the reason is the one a limited choice needs.
  await user.click(within(dialog).getByRole("checkbox", { name: "corp-marketplace" }));
  expect(save).toBeDisabled();
  expect(save).toHaveAccessibleDescription(/Choose at least one marketplace/);

  await user.click(within(dialog).getByRole("checkbox", { name: "data" }));
  expect(save).toBeEnabled();
  expect(save).toHaveAccessibleDescription(/Saving changes the reach now/);
  await user.click(save);
  await waitFor(() => expect(patched).toHaveLength(1));
  expect(patched[0]).toEqual({ id: 22, body: { scopes: ["data"] } });
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  // The list shows the reach the server now holds.
  const row = await screen.findByRole("row", { name: /ci-corp-only/ });
  await waitFor(() => expect(within(row).getByText("data")).toBeInTheDocument());
  expect(within(row).queryByText("corp-marketplace")).not.toBeInTheDocument();
});

/**
 * "Every marketplace" is the explicit `allMarketplaces: true`, an empty list is never sent, and a
 * widening is said so before it is saved. A narrowing is not warned about.
 *
 * @SVCs SVC_GW_AUTH_0108
 */
test("widening_a_tokens_reach_is_warned_about_and_every_marketplace_is_stated_as_all_marketplaces", async () => {
  threeMarketplaces();
  const patched = statefulTokens(scopedTokenViews);
  const user = userEvent.setup();
  renderPage();
  const dialog = await openEdit(user, "ci-corp-only");

  await user.click(await within(dialog).findByRole("checkbox", { name: "tools" }));
  expect(within(dialog).getByTestId("widening-warning")).toHaveTextContent("it will also fetch 'tools'");
  await user.click(within(dialog).getByRole("checkbox", { name: "tools" }));
  expect(within(dialog).queryByTestId("widening-warning")).not.toBeInTheDocument();

  await user.click(within(dialog).getByRole("button", { name: "Every marketplace" }));
  expect(within(dialog).getByTestId("widening-warning")).toHaveTextContent(
    "it will fetch every marketplace this gateway serves you",
  );
  await user.click(within(dialog).getByRole("button", { name: "Save reach" }));
  await waitFor(() => expect(patched).toHaveLength(1));
  expect(patched[0]).toEqual({ id: 22, body: { allMarketplaces: true } });
  // No edit ever carries an empty list: that is the one body the API refuses.
  expect(patched.every((edit) => edit.body.scopes === undefined || edit.body.scopes.length > 0)).toBe(true);
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

  // An unlimited token has nothing to widen: choosing names only narrows it.
  const second = await openEdit(user, "unscoped-laptop");
  await user.click(within(second).getByRole("button", { name: "Chosen marketplaces" }));
  await user.click(await within(second).findByRole("checkbox", { name: "tools" }));
  expect(within(second).queryByTestId("widening-warning")).not.toBeInTheDocument();
});

/**
 * A refusal is shown as the API states it, the dialog stays open, and a new choice clears it.
 *
 * @SVCs SVC_GW_AUTH_0108
 */
test("a_refused_reach_edit_is_shown_as_the_api_states_it", async () => {
  threeMarketplaces();
  server.use(
    http.get("/api/v1/tokens", () => HttpResponse.json(scopedTokenViews)),
    http.patch("/api/v1/tokens/:id", () =>
      HttpResponse.json(
        { detail: "unknown scope 'tools': scopes name registered marketplaces or the catalog" },
        { status: 422 },
      ),
    ),
  );
  const user = userEvent.setup();
  renderPage();
  const dialog = await openEdit(user, "ci-corp-only");
  await user.click(await within(dialog).findByRole("checkbox", { name: "tools" }));
  await user.click(within(dialog).getByRole("button", { name: "Save reach" }));
  expect(await within(dialog).findByRole("alert")).toHaveTextContent(
    "unknown scope 'tools': scopes name registered marketplaces or the catalog",
  );
  expect(screen.getByRole("dialog")).toBeInTheDocument();

  // A token that died while the dialog was open is refused the same way.
  server.use(
    http.patch("/api/v1/tokens/:id", () =>
      HttpResponse.json(
        { detail: "token 22 is not live; a revoked or expired token's scopes cannot be changed" },
        { status: 409 },
      ),
    ),
  );
  await user.click(within(dialog).getByRole("checkbox", { name: "data" }));
  expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
  await user.click(within(dialog).getByRole("button", { name: "Save reach" }));
  expect(await within(dialog).findByRole("alert")).toHaveTextContent("is not live");
});

/** Only a live token can be edited: revoked and expired rows offer no control. */
test("only_a_live_token_offers_the_edit_control", async () => {
  server.use(
    http.get("/api/v1/tokens", () =>
      HttpResponse.json([
        { ...scopedTokenViews[0]!, id: 31, name: "live-one" },
        { ...scopedTokenViews[0]!, id: 32, name: "revoked-one", revokedAt: "2026-08-15T10:00:00Z" },
        { ...scopedTokenViews[0]!, id: 33, name: "lapsed-one", expiresAt: "2026-08-15T10:00:00Z" },
      ]),
    ),
  );
  renderPage();
  expect(await screen.findByRole("button", { name: "Edit reach of token live-one" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Edit reach of token revoked-one" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Edit reach of token lapsed-one" })).not.toBeInTheDocument();
});

/** The edit holds no secret: the response is the token, and the mutation cache keeps none of it. */
test("a_reach_edit_leaves_no_secret_in_the_mutation_cache", async () => {
  threeMarketplaces();
  statefulTokens(scopedTokenViews);
  const user = userEvent.setup();
  renderPage();
  const dialog = await openEdit(user, "ci-corp-only");
  await user.click(await within(dialog).findByRole("checkbox", { name: "data" }));
  await user.click(within(dialog).getByRole("button", { name: "Save reach" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  expect(JSON.stringify(queryClient.getMutationCache().getAll().map((mutation) => mutation.state))).not.toContain(
    "sgw_",
  );
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { machineViews } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { SystemTokensPage } from "./system-tokens";

/**
 * Editing a system access token's scopes from the list (GW_AUTH_0089): the control, its rules, its
 * warning, its refusals. Create-form behaviour is in system-tokens.test.tsx.
 */

function signedInAsAdministrator() {
  server.use(
    http.get("/api/v1/me", () =>
      HttpResponse.json({ username: "ada", roles: [{ role: "admin", source: "config" }], claimsTruncated: false }),
    ),
  );
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <SystemTokensPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

type Patched = { id: number; apiScopes?: string[] };

/** A credential list that remembers a PATCH, as the server would, and the bodies it was sent. */
function statefulCredentials(): Patched[] {
  let credentials = machineViews.map((credential) => ({ ...credential }));
  const patched: Patched[] = [];
  server.use(
    http.get("/api/v1/tokens/machine", () => HttpResponse.json(credentials)),
    http.patch("/api/v1/tokens/machine/:id", async ({ request, params }) => {
      const body = (await request.json()) as { apiScopes?: string[] };
      const id = Number(params.id);
      patched.push({ id, apiScopes: body.apiScopes });
      credentials = credentials.map((credential) =>
        credential.id === id ? { ...credential, apiScopes: body.apiScopes } : credential,
      );
      return HttpResponse.json(credentials.find((credential) => credential.id === id));
    }),
  );
  return patched;
}

async function openEdit(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole("button", { name: `Actions for token ${name}` }));
  await user.click(await screen.findByRole("menuitem", { name: "Edit scopes…" }));
  return screen.findByRole("dialog", { name: `Edit scopes of '${name}'` });
}

/**
 * The edit starts from the scopes the token holds; it cannot be saved with none ticked or with
 * nothing changed, and says why; adding scopes says who gets them; the list shows the result.
 *
 * @SVCs SVC_GW_AUTH_0109
 */
test("editing_a_system_tokens_scopes_is_disabled_until_valid_and_different_and_warns_when_it_adds", async () => {
  signedInAsAdministrator();
  const patched = statefulCredentials();
  const user = userEvent.setup();
  renderPage();
  const dialog = await openEdit(user, "platform-pipeline");
  const save = within(dialog).getByRole("button", { name: "Save scopes" });

  expect(await within(dialog).findByRole("checkbox", { name: "estate:read" })).toBeChecked();
  expect(within(dialog).getByRole("checkbox", { name: "marketplaces:read" })).not.toBeChecked();
  expect(save).toBeDisabled();
  expect(save).toHaveAccessibleDescription(/current scopes/);

  // None ticked: a system access token must hold one, and the reason says what to do instead.
  await user.click(within(dialog).getByRole("checkbox", { name: "estate:read" }));
  expect(save).toBeDisabled();
  expect(save).toHaveAccessibleDescription(/at least one scope.*revoke/);

  // Back to what it held is unchanged again; adding one is a change, and a warning.
  await user.click(within(dialog).getByRole("checkbox", { name: "estate:read" }));
  expect(save).toBeDisabled();
  expect(within(dialog).queryByTestId("widening-warning")).not.toBeInTheDocument();
  await user.click(within(dialog).getByRole("checkbox", { name: "marketplaces:register" }));
  expect(save).toBeEnabled();
  expect(within(dialog).getByTestId("widening-warning")).toHaveTextContent(
    "This adds marketplaces:register: whoever holds the existing secret has it from the next request.",
  );

  await user.click(save);
  await waitFor(() => expect(patched).toHaveLength(1));
  expect(patched[0]).toEqual({ id: 21, apiScopes: ["estate:read", "marketplaces:register"] });
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  const row = await screen.findByRole("row", { name: /platform-pipeline/ });
  await waitFor(() => expect(within(row).getByText("estate:read, marketplaces:register")).toBeInTheDocument());
});

/** Narrowing adds nothing, so it is not warned about. */
test("narrowing_a_system_tokens_scopes_is_not_warned_about", async () => {
  signedInAsAdministrator();
  server.use(
    http.get("/api/v1/tokens/machine", () =>
      HttpResponse.json([{ ...machineViews[0]!, apiScopes: ["estate:read", "marketplaces:read"] }]),
    ),
  );
  const user = userEvent.setup();
  renderPage();
  const dialog = await openEdit(user, "platform-pipeline");
  await user.click(await within(dialog).findByRole("checkbox", { name: "marketplaces:read" }));
  expect(within(dialog).getByRole("button", { name: "Save scopes" })).toBeEnabled();
  expect(within(dialog).queryByTestId("widening-warning")).not.toBeInTheDocument();
});

/** A refusal is shown as the API states it and the dialog stays open. */
test("a_refused_scope_edit_is_shown_as_the_api_states_it", async () => {
  signedInAsAdministrator();
  server.use(
    http.patch("/api/v1/tokens/machine/:id", () =>
      HttpResponse.json(
        { detail: "unknown API scope 'estate:reed': API scopes are named per concern and there is no wildcard" },
        { status: 422 },
      ),
    ),
  );
  const user = userEvent.setup();
  renderPage();
  const dialog = await openEdit(user, "platform-pipeline");
  await user.click(await within(dialog).findByRole("checkbox", { name: "marketplaces:read" }));
  await user.click(within(dialog).getByRole("button", { name: "Save scopes" }));
  expect(await within(dialog).findByRole("alert")).toHaveTextContent("unknown API scope 'estate:reed'");
  expect(screen.getByRole("dialog")).toBeInTheDocument();
});

/** Only a live credential can be edited. */
test("only_a_live_system_token_offers_the_edit_control", async () => {
  signedInAsAdministrator();
  server.use(
    http.get("/api/v1/tokens/machine", () =>
      HttpResponse.json([
        { ...machineViews[0]!, id: 31, name: "live-job" },
        { ...machineViews[0]!, id: 32, name: "revoked-job", revokedAt: "2026-08-15T10:00:00Z" },
        { ...machineViews[0]!, id: 33, name: "lapsed-job", expiresAt: "2026-08-15T10:00:00Z" },
      ]),
    ),
  );
  renderPage();
  // Only a live credential has an actions menu, and so only it can have its scopes edited.
  expect(await screen.findByRole("button", { name: "Actions for token live-job" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Actions for token revoked-job" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Actions for token lapsed-job" })).not.toBeInTheDocument();
});

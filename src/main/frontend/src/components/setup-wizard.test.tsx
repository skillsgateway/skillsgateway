import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { expect, test } from "vitest";
import { Button } from "@/components/ui/button";
import { marketplace } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { http, HttpResponse } from "msw";
import { SetupWizard } from "./setup-wizard";

/** Kept so a test can look in the cache the mounted wizard leaves behind. */
let queryClient: QueryClient;

/** The wizard as the detail page hosts it: unmounted when closed, so its state cannot survive. */
function Host({ serving = true }: { serving?: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button onClick={() => setOpen(true)}>Connect a client</Button>
      {open ? (
        <SetupWizard
          marketplace="corp-marketplace"
          serving={serving}
          onClose={() => setOpen(false)}
        />
      ) : null}
    </>
  );
}

function renderWizard(serving = true) {
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <Host serving={serving} />
    </QueryClientProvider>,
  );
}

test("commands_are_composed_from_the_browsing_origin_and_the_facade_path", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  const origin = window.location.origin;
  expect(await screen.findByTestId("wizard-add-command")).toHaveTextContent(
    `claude plugin marketplace add ${origin}/git/corp-marketplace.git`,
  );
  expect(screen.getByTestId("wizard-credential-config")).toHaveTextContent(window.location.host);
  expect(screen.getByTestId("wizard-clone-command")).toHaveTextContent("/git/corp-marketplace");
  // No token minted yet, and none ever goes into a command: nothing to show in its field.
  expect(screen.queryByTestId("wizard-token-value")).not.toBeInTheDocument();
});

/**
 * The credential step works on a machine with no git configuration: it names the store helper for
 * this origin only, then reads the token at a prompt that does not echo it, so the token is in
 * neither the command nor the shell history. The exact text is asserted because it is the
 * contract the e2e run proves in a real shell.
 *
 * @SVCs SVC_GW_AUTH_0069
 */
test("the_credential_command_names_the_store_helper_for_this_origin_only_and_reads_the_token_at_a_prompt", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  const { origin, host } = window.location;
  const scheme = window.location.protocol.replace(":", "");
  expect((await screen.findByTestId("wizard-credential-config")).textContent).toBe(
    `git config --global 'credential.${origin}.helper' store\n` +
      `printf 'Token: ' && read -rs SGW_TOKEN && echo && ` +
      `printf 'protocol=${scheme}\\nhost=${host}\\nusername=token\\npassword=%s\\n' "$SGW_TOKEN" ` +
      `| git credential approve; unset SGW_TOKEN`,
  );
  const note = screen.getByTestId("wizard-credential-note");
  expect(note).toHaveTextContent(/plaintext/);
  expect(note).toHaveTextContent(/~\/\.git-credentials/);
  expect(note).toHaveTextContent(/keychain/);
  expect(note).toHaveTextContent(/shell history/);
});

/**
 * A minted token is shown in its own field, never inside a command: the commands read the same
 * before and after minting, and the field is gone with the wizard.
 *
 * @SVCs SVC_GW_AUTH_0069
 */
test("a_minted_token_is_shown_in_its_own_field_and_in_no_command", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  const before = (await screen.findByTestId("wizard-credential-config")).textContent;
  await user.clear(screen.getByLabelText("Token name"));
  await user.type(screen.getByLabelText("Token name"), "my-laptop");
  await user.click(screen.getByRole("button", { name: "Create token" }));
  expect(await screen.findByTestId("wizard-token-value")).toHaveTextContent("sgw_cleartext_shown_once");
  expect(screen.getByRole("button", { name: "Copy token" })).toBeInTheDocument();
  expect(screen.getByTestId("wizard-credential-config").textContent).toBe(before);
  for (const id of ["wizard-credential-config", "wizard-add-command", "wizard-clone-command"]) {
    expect(screen.getByTestId(id)).not.toHaveTextContent("sgw_cleartext_shown_once");
  }
});

/**
 * The default name `<marketplace>-client` is not a scope: the token step says what it reaches.
 *
 * @SVCs SVC_GW_AUTH_0070
 */
test("the_token_step_says_the_token_is_not_limited_to_this_marketplace", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  const reach = await screen.findByTestId("wizard-token-reach");
  expect(reach).toHaveTextContent("A token created here is not limited to 'corp-marketplace'");
  expect(reach).toHaveTextContent(/every marketplace this gateway serves/);
});

/**
 * The ledger records the fetches the facade serves; a refused request is not written, so the
 * wizard must not say every fetch is recorded.
 *
 * @SVCs SVC_GW_AUTH_0070
 */
test("the_ledger_statement_covers_served_fetches_and_not_refused_requests", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  await screen.findByTestId("wizard-add-command");
  expect(screen.queryByText(/every fetch/i)).not.toBeInTheDocument();
  const ledger = screen.getByTestId("wizard-ledger-note");
  expect(ledger).toHaveTextContent(/Each fetch it serves is recorded on the audit ledger/);
  expect(ledger).toHaveTextContent(/refused request .* is not/);
});

test("create_token_is_disabled_until_the_name_is_non_blank", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  const createButton = await screen.findByRole("button", { name: "Create token" });
  // The field arrives with a sensible default, so the control starts enabled — the rule under
  // test is that emptying it disables the control again, and that whitespace is not a name.
  expect(screen.getByLabelText("Token name")).toHaveValue("corp-marketplace-client");
  expect(createButton).toBeEnabled();
  await user.clear(screen.getByLabelText("Token name"));
  expect(createButton).toBeDisabled();
  await user.type(screen.getByLabelText("Token name"), "   ");
  expect(createButton).toBeDisabled();
  await user.clear(screen.getByLabelText("Token name"));
  await user.type(screen.getByLabelText("Token name"), "my-laptop");
  expect(createButton).toBeEnabled();
});

/** @SVCs SVC_GW_AUTH_0094 */
test("the_lifetime_defaults_to_a_bounded_value_rather_than_to_never_expiring", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  const group = within(await screen.findByRole("group", { name: "Expires" }));
  expect(group.getByRole("button", { name: "30 days" })).toHaveAttribute("aria-pressed", "true");
  expect(group.queryByRole("button", { name: "No expiry" })).not.toBeInTheDocument();
  expect(group.getByRole("button", { name: "1 year" })).toHaveAttribute("aria-pressed", "false");
});

test("choosing_a_lifetime_moves_the_pressed_state_to_it", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  const group = within(await screen.findByRole("group", { name: "Expires" }));
  await user.click(group.getByRole("button", { name: "180 days" }));
  expect(group.getByRole("button", { name: "180 days" })).toHaveAttribute("aria-pressed", "true");
  expect(group.getByRole("button", { name: "30 days" })).toHaveAttribute("aria-pressed", "false");
  // The choice is a choice, not a submit: the form must still be waiting for "Create token".
  expect(screen.queryByText(/Token created/)).not.toBeInTheDocument();
});

test("every_snippet_copies_through_an_icon_button_named_for_what_it_copies", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  // The credential line leads on the box, not on a differently shaped control: the three
  // snippets are one family, each with the same corner icon button.
  expect(await screen.findByRole("button", { name: "Copy credential command" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Copy marketplace add command" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Copy clone command" })).toBeInTheDocument();
});

test("a_held_marketplace_says_a_clone_is_answered_with_404", async () => {
  const user = userEvent.setup();
  renderWizard(false);
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  const notice = await screen.findByTestId("setup-held-notice");
  expect(notice).toHaveTextContent("404");
  expect(notice).toHaveTextContent(/Until a snapshot is approved/);
});

test("a_serving_marketplace_shows_no_held_notice", async () => {
  const user = userEvent.setup();
  renderWizard(true);
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  await screen.findByTestId("wizard-add-command");
  expect(screen.queryByTestId("setup-held-notice")).not.toBeInTheDocument();
});

test("a_minted_token_is_shown_only_while_the_wizard_is_open", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  await user.clear(await screen.findByLabelText("Token name"));
  await user.type(screen.getByLabelText("Token name"), "my-laptop");
  await user.click(screen.getByRole("button", { name: "Create token" }));
  // The MSW-issued cleartext is shown once, in its own field.
  expect(await screen.findByText(/Token created/)).toBeInTheDocument();
  expect(screen.getByTestId("wizard-token-value")).toHaveTextContent("sgw_cleartext_shown_once");
  // The clone line carries no userinfo: the credential helper holds the token.
  expect(screen.getByTestId("wizard-clone-command")).not.toHaveTextContent("token:");

  // Close and reopen: the wizard was unmounted and the secret is gone with it.
  await user.click(screen.getByRole("button", { name: "Done" }));
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  await screen.findByTestId("wizard-credential-config");
  expect(screen.queryByTestId("wizard-token-value")).not.toBeInTheDocument();
  expect(screen.queryByText(/sgw_cleartext_shown_once/)).not.toBeInTheDocument();
});

type Posted = { name?: string; scopes?: string[] };

/** Records the create requests and answers each with a token holding the scopes it asked for. */
function recordPosts(): Posted[] {
  const posted: Posted[] = [];
  server.use(
    http.get("/api/v1/marketplaces", () =>
      HttpResponse.json([
        { ...marketplace, id: 1, name: "corp-marketplace" },
        { ...marketplace, id: 2, name: "tools" },
      ]),
    ),
    http.post("/api/v1/tokens", async ({ request }) => {
      const body = (await request.json()) as Posted;
      posted.push(body);
      return HttpResponse.json(
        { id: 9, name: body.name, token: "sgw_cleartext_shown_once", scopes: body.scopes ?? [] },
        { status: 201 },
      );
    }),
  );
  return posted;
}

/**
 * The default is every marketplace and sends no scopes; switching to the limited choice starts
 * from the marketplace the wizard was opened on, which can be widened (GW_AUTH_0079).
 *
 * @SVCs SVC_GW_AUTH_0097
 */
test("the_wizard_creates_an_unlimited_token_by_default_and_a_limited_one_starting_from_its_own_marketplace", async () => {
  const posted = recordPosts();
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  const choice = within(await screen.findByRole("group", { name: "Marketplaces" }));
  expect(choice.getByRole("button", { name: "Every marketplace" })).toHaveAttribute("aria-pressed", "true");
  await user.click(screen.getByRole("button", { name: "Create token" }));
  await screen.findByTestId("wizard-token-value");
  expect(posted).toHaveLength(1);
  expect(posted[0]).not.toHaveProperty("scopes");

  // A second wizard, limited: its own marketplace is already ticked, and tools is added.
  await user.click(screen.getByRole("button", { name: "Done" }));
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  await user.click(await screen.findByRole("button", { name: "Chosen marketplaces" }));
  expect(await screen.findByRole("checkbox", { name: "corp-marketplace" })).toBeChecked();
  await user.click(screen.getByRole("checkbox", { name: "tools" }));
  await user.click(screen.getByRole("button", { name: "Create token" }));
  await screen.findByTestId("wizard-token-value");
  expect(posted[1]!.scopes).toEqual(["corp-marketplace", "tools"]);
});

/**
 * The statement of what the token reaches is true for whatever was chosen, before the token is
 * made and after, and after it is the server's account of the token (GW_AUTH_0063).
 *
 * @SVCs SVC_GW_AUTH_0099
 */
test("the_reach_statement_follows_the_choice_before_and_after_the_token_is_created", async () => {
  recordPosts();
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  const reach = await screen.findByTestId("wizard-token-reach");
  expect(reach).toHaveTextContent("A token created here is not limited to 'corp-marketplace'");

  await user.click(screen.getByRole("button", { name: "Chosen marketplaces" }));
  await user.click(await screen.findByRole("checkbox", { name: "tools" }));
  expect(reach).toHaveTextContent("A token created here fetches only 'corp-marketplace', 'tools';");
  expect(reach).not.toHaveTextContent("not limited");
  expect(reach).toHaveTextContent(/one credential per gateway/);

  // Nothing chosen: nothing is created, and the text does not claim a reach for a token that
  // cannot exist.
  await user.click(screen.getByRole("checkbox", { name: "tools" }));
  await user.click(screen.getByRole("checkbox", { name: "corp-marketplace" }));
  expect(reach).toHaveTextContent("No token is created until at least one marketplace is chosen.");
  expect(screen.getByRole("button", { name: "Create token" })).toBeDisabled();

  await user.click(screen.getByRole("checkbox", { name: "tools" }));
  await user.click(screen.getByRole("button", { name: "Create token" }));
  await screen.findByTestId("wizard-token-value");
  expect(screen.getByTestId("wizard-token-reach")).toHaveTextContent(
    "The token created here fetches only 'tools';",
  );
});

/**
 * @SVCs SVC_GW_AUTH_0099
 */
test("an_unlimited_token_is_described_as_unlimited_after_it_is_created", async () => {
  recordPosts();
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  await user.click(await screen.findByRole("button", { name: "Create token" }));
  await screen.findByTestId("wizard-token-value");
  expect(screen.getByTestId("wizard-token-reach")).toHaveTextContent(
    "The token created here is not limited to 'corp-marketplace'",
  );
});

/**
 * The cleartext is in the wizard's own field and in no cache entry, with the wizard still mounted
 * (GW_AUTH_0081).
 *
 * @SVCs SVC_GW_AUTH_0100
 */
test("the_wizards_cleartext_is_not_left_in_the_mutation_cache", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  await user.click(await screen.findByRole("button", { name: "Create token" }));
  expect(await screen.findByTestId("wizard-token-value")).toHaveTextContent("sgw_cleartext_shown_once");
  await waitFor(() => expect(queryClient.getMutationCache().getAll()).toEqual([]));
  expect(JSON.stringify(queryClient.getQueryCache().getAll().map((query) => query.state.data))).not.toContain(
    "sgw_cleartext_shown_once",
  );
  expect(screen.getByTestId("wizard-token-value")).toHaveTextContent("sgw_cleartext_shown_once");
});

/**
 * Hardening: the wizard's own marketplace is ticked on switching even when the list read does not
 * hold it (removed, or not yet listed), and stays a row that can be unticked.
 *
 * @SVCs SVC_GW_AUTH_0097
 */
test("the_wizards_own_marketplace_stays_a_row_that_can_be_unticked_even_if_the_list_lacks_it", async () => {
  server.use(
    http.get("/api/v1/marketplaces", () => HttpResponse.json([{ ...marketplace, id: 2, name: "tools" }])),
  );
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  await user.click(await screen.findByRole("button", { name: "Chosen marketplaces" }));
  const own = await screen.findByRole("checkbox", { name: "corp-marketplace" });
  expect(own).toBeChecked();
  await user.click(own);
  // Unticked, a name the list does not hold has no reason to stay as a row.
  expect(screen.queryByRole("checkbox", { name: "corp-marketplace" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Create token" })).toBeDisabled();
});

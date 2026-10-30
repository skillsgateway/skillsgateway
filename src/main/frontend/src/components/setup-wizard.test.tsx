import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { expect, test } from "vitest";
import { Button } from "@/components/ui/button";
import { SetupWizard } from "./setup-wizard";

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
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
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
    `git config --global credential.${origin}.helper store\n` +
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
  expect(reach).toHaveTextContent("not limited to 'corp-marketplace'");
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

test("the_lifetime_defaults_to_a_bounded_value_rather_than_to_never_expiring", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  const group = within(await screen.findByRole("group", { name: "Expires" }));
  expect(group.getByRole("button", { name: "30 days" })).toHaveAttribute("aria-pressed", "true");
  expect(group.getByRole("button", { name: "No expiry" })).toHaveAttribute("aria-pressed", "false");
});

test("choosing_a_lifetime_moves_the_pressed_state_to_it", async () => {
  const user = userEvent.setup();
  renderWizard();
  await user.click(screen.getByRole("button", { name: "Connect a client" }));
  const group = within(await screen.findByRole("group", { name: "Expires" }));
  await user.click(group.getByRole("button", { name: "7 days" }));
  expect(group.getByRole("button", { name: "7 days" })).toHaveAttribute("aria-pressed", "true");
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

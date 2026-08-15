import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test } from "vitest";
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

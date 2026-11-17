import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, userEvent, within } from "storybook/test";
import { machineScopes, machineViews } from "@/test/msw-handlers";
import { EditSystemTokenScopesDialog } from "./edit-system-token-scopes-dialog";

/** A client already holding the scope list, so the story needs no server. */
function Harness() {
  const [client] = useState(() => {
    const seeded = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    seeded.setQueryData(["machine-token-scopes"], machineScopes);
    return seeded;
  });
  return (
    <QueryClientProvider client={client}>
      <EditSystemTokenScopesDialog token={machineViews[0]!} onClose={() => {}} />
    </QueryClientProvider>
  );
}

const meta = {
  title: "Tokens/EditSystemTokenScopesDialog",
  component: EditSystemTokenScopesDialog,
} satisfies Meta<typeof EditSystemTokenScopesDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

const body = (canvasElement: HTMLElement) => within(canvasElement.ownerDocument.body);

/** Opened on a token: the scopes it holds are ticked and there is nothing to save yet, said in words. */
export const CurrentScopes: Story = {
  args: { token: machineViews[0]!, onClose: () => {} },
  render: () => <Harness />,
  play: async ({ canvasElement }) => {
    const dialog = body(canvasElement);
    await expect(await dialog.findByRole("heading", { name: "Edit scopes of 'platform-pipeline'" })).toBeVisible();
    await expect(dialog.getByRole("checkbox", { name: "estate:read" })).toBeChecked();
    await expect(dialog.getByRole("button", { name: "Save scopes" })).toBeDisabled();
    await expect(dialog.getByText(/current scopes/)).toBeVisible();
  },
};

/** Adding a scope says who gets it: the holder of the secret already in use. */
export const AddsAScope: Story = {
  args: { token: machineViews[0]!, onClose: () => {} },
  render: () => <Harness />,
  play: async ({ canvasElement }) => {
    const dialog = body(canvasElement);
    await userEvent.click(await dialog.findByRole("checkbox", { name: "marketplaces:register" }));
    await expect(dialog.getByTestId("widening-warning")).toHaveTextContent(
      "This adds marketplaces:register: whoever holds the existing secret has it from the next request.",
    );
    await expect(dialog.getByRole("button", { name: "Save scopes" })).toBeEnabled();
  },
};

/** A system access token holds at least one scope: none ticked cannot be saved, and says what to do instead. */
export const NoScopeTicked: Story = {
  args: { token: machineViews[0]!, onClose: () => {} },
  render: () => <Harness />,
  play: async ({ canvasElement }) => {
    const dialog = body(canvasElement);
    await userEvent.click(await dialog.findByRole("checkbox", { name: "estate:read" }));
    await expect(dialog.getByRole("button", { name: "Save scopes" })).toBeDisabled();
    await expect(dialog.getByText(/To remove them all, revoke the token/)).toBeVisible();
  },
};

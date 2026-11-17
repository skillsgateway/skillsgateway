import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, userEvent, within } from "storybook/test";
import { manyMarketplaces, scopedTokenViews } from "@/test/msw-handlers";
import { EditTokenScopesDialog } from "./edit-token-scopes-dialog";

/** A client already holding the reads the picker makes, so the story needs no server. */
function Harness({ tokenIndex }: { tokenIndex: number }) {
  const [client] = useState(() => {
    const seeded = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    seeded.setQueryData(["marketplaces"], [
      ...manyMarketplaces(3).map((item, index) => ({ ...item, name: ["corp-marketplace", "tools", "data"][index] })),
    ]);
    return seeded;
  });
  return (
    <QueryClientProvider client={client}>
      <EditTokenScopesDialog token={scopedTokenViews[tokenIndex]!} onClose={() => {}} />
    </QueryClientProvider>
  );
}

const meta = {
  title: "Tokens/EditTokenScopesDialog",
  component: EditTokenScopesDialog,
} satisfies Meta<typeof EditTokenScopesDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

const body = (canvasElement: HTMLElement) => within(canvasElement.ownerDocument.body);

/** Opened on a limited token: its own reach is ticked and there is nothing to save yet, said in words. */
export const CurrentReach: Story = {
  args: { token: scopedTokenViews[1]!, onClose: () => {} },
  render: () => <Harness tokenIndex={1} />,
  play: async ({ canvasElement }) => {
    const dialog = body(canvasElement);
    await expect(await dialog.findByRole("heading", { name: "Edit reach of 'ci-corp-only'" })).toBeVisible();
    await expect(dialog.getByRole("checkbox", { name: "corp-marketplace" })).toBeChecked();
    await expect(dialog.getByRole("button", { name: "Save reach" })).toBeDisabled();
    await expect(dialog.getByText(/current reach/)).toBeVisible();
  },
};

/** Widening a limited token to one more marketplace: the warning names it, and saving is open. */
export const AddsAMarketplace: Story = {
  args: { token: scopedTokenViews[1]!, onClose: () => {} },
  render: () => <Harness tokenIndex={1} />,
  play: async ({ canvasElement }) => {
    const dialog = body(canvasElement);
    await userEvent.click(await dialog.findByRole("checkbox", { name: "tools" }));
    await expect(dialog.getByTestId("widening-warning")).toHaveTextContent("it will also fetch 'tools'");
    await expect(dialog.getByRole("button", { name: "Save reach" })).toBeEnabled();
  },
};

/** The limited choice with nothing ticked cannot be saved, and the reason is on screen. */
export const NamesNothing: Story = {
  args: { token: scopedTokenViews[1]!, onClose: () => {} },
  render: () => <Harness tokenIndex={1} />,
  play: async ({ canvasElement }) => {
    const dialog = body(canvasElement);
    await userEvent.click(await dialog.findByRole("checkbox", { name: "corp-marketplace" }));
    await expect(dialog.getByRole("button", { name: "Save reach" })).toBeDisabled();
    await expect(dialog.getByText(/Choose at least one marketplace, or Every marketplace/)).toBeVisible();
  },
};

/** Going to every marketplace is a widening and says so. */
export const ToEveryMarketplace: Story = {
  args: { token: scopedTokenViews[2]!, onClose: () => {} },
  render: () => <Harness tokenIndex={2} />,
  play: async ({ canvasElement }) => {
    const dialog = body(canvasElement);
    await userEvent.click(await dialog.findByRole("button", { name: "Every marketplace" }));
    await expect(dialog.getByTestId("widening-warning")).toHaveTextContent(
      "it will fetch every marketplace this gateway serves you",
    );
  },
};

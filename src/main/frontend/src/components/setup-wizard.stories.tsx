import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { SetupWizard } from "./setup-wizard";

const meta = {
  title: "Marketplaces/SetupWizard",
  component: SetupWizard,
  decorators: [
    (Story) => (
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <Story />
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof SetupWizard>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * A marketplace that is serving: the credential command leads as the one labelled copy control,
 * and the lifetime arrives bounded rather than permanent.
 */
export const Serving: Story = {
  args: { marketplace: "corp-marketplace", serving: true, onClose: () => {} },
  play: async ({ canvasElement }) => {
    // The dialog renders in a portal attached to the document body.
    const body = within(canvasElement.ownerDocument.body);
    await expect(await body.findByRole("button", { name: "Copy credential command" })).toBeVisible();
    await expect(body.getByLabelText("Expires")).toHaveValue("P30D");
    await expect(body.queryByTestId("setup-held-notice")).toBeNull();
  },
};

/**
 * A marketplace whose only snapshot is still held: the commands are correct but nothing answers
 * them yet, and the wizard says so in the terms the consumer's client will use.
 */
export const SnapshotStillHeld: Story = {
  args: { marketplace: "corp-marketplace", serving: false, onClose: () => {} },
  play: async ({ canvasElement }) => {
    const body = within(canvasElement.ownerDocument.body);
    await expect(await body.findByTestId("setup-held-notice")).toHaveTextContent("404");
  },
};

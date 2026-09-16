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
    await expect(
      within(body.getByRole("group", { name: "Expires" })).getByRole("button", { name: "30 days" }),
    ).toHaveAttribute("aria-pressed", "true");
    await expect(body.queryByTestId("setup-held-notice")).toBeNull();
  },
};

/**
 * The same wizard in the portal's dark theme.
 *
 * The lifetime control is a themed button group rather than a native `<select>` precisely so
 * that this story is a fair picture of it: a browser paints a select's open list with its own
 * chrome, and nothing in the theme reaches inside it.
 */
export const ServingDark: Story = {
  args: { marketplace: "corp-marketplace", serving: true, onClose: () => {} },
  parameters: { theme: "dark" },
  play: async ({ canvasElement }) => {
    const body = within(canvasElement.ownerDocument.body);
    await expect(
      within(await body.findByRole("group", { name: "Expires" })).getByRole("button", {
        name: "No expiry",
      }),
    ).toHaveAttribute("aria-pressed", "false");
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

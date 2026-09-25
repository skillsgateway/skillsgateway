import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { MemoryRouter } from "react-router-dom";
import { expect, userEvent, within } from "storybook/test";
import type { EstateReconciliation } from "@/api/queries";
import { estateReport } from "@/test/msw-handlers";
import { RemoveMarketplace, RemoveMarketplaceDialog } from "./remove-marketplace";

const MARKETPLACE = "corp-marketplace";

/** The estate report is seeded rather than fetched: the story shows what the card does with it. */
function withEstate(report: EstateReconciliation) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(["estate"], report);
  return client;
}

const declared: EstateReconciliation = {
  ...estateReport,
  entries: [{ kind: "marketplace", name: MARKETPLACE, action: "unchanged" }],
  unchanged: 1,
};

const meta = {
  title: "Marketplace/RemoveMarketplace",
  component: RemoveMarketplace,
  args: { name: MARKETPLACE },
  decorators: [
    (Story, context) => (
      <QueryClientProvider client={withEstate(context.parameters.estate ?? estateReport)}>
        <MemoryRouter>
          <div style={{ maxWidth: 720 }}>
            <Story />
          </div>
        </MemoryRouter>
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof RemoveMarketplace>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing declares it: the control is available. */
export const Available: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("button", { name: `Remove ${MARKETPLACE}…` })).toBeEnabled();
  },
};

/** The estate declares it: unavailable, and the reason is on the card. */
export const Declared: Story = {
  parameters: { estate: declared },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const button = canvas.getByRole("button", { name: `Remove ${MARKETPLACE}…` });
    await expect(button).toBeDisabled();
    await expect(button).toHaveAccessibleDescription(/declared in the estate configuration/);
  },
};

/** The confirmation, with a reason written: every consequence is stated before the act. */
export const Confirmation: StoryObj<typeof RemoveMarketplaceDialog> = {
  render: () => <RemoveMarketplaceDialog name={MARKETPLACE} onClose={() => {}} />,
  play: async ({ canvasElement }) => {
    const body = within(canvasElement.ownerDocument.body);
    const dialog = within(await body.findByRole("dialog", { name: `Remove ${MARKETPLACE}` }));
    const confirm = dialog.getByRole("button", { name: `Remove ${MARKETPLACE}` });
    await expect(confirm).toBeDisabled();
    await userEvent.type(dialog.getByLabelText("Reason"), "upstream moved");
    await expect(confirm).toBeEnabled();
  },
};

export const ConfirmationDark: StoryObj<typeof RemoveMarketplaceDialog> = {
  ...Confirmation,
  parameters: { theme: "dark" },
};

/** Names have no length limit and no spaces: at phone width the control's label wraps. */
export const LongNameAtPhoneWidth: Story = {
  args: { name: "a-marketplace-with-a-name-far-longer-than-any-phone-screen-is-wide" },
  render: (args) => (
    <div style={{ width: 320 }}>
      <RemoveMarketplace {...args} />
    </div>
  ),
};

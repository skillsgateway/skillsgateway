import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { MemoryRouter } from "react-router-dom";
import { expect, userEvent, within } from "storybook/test";
import type { EstateReconciliation } from "@/api/queries";
import { estateReport } from "@/test/msw-handlers";
import { EditMarketplaceUrl, EditMarketplaceUrlDialog } from "./edit-marketplace-url";

const MARKETPLACE = "corp-marketplace";
const URL = "https://github.com/corp/marketplac.git";

/** The estate report is seeded rather than fetched: the story shows what the control does with it. */
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
  title: "Marketplace/EditMarketplaceUrl",
  component: EditMarketplaceUrl,
  args: { name: MARKETPLACE, url: URL, snapshotCount: 0 },
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
} satisfies Meta<typeof EditMarketplaceUrl>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing ingested yet: the URL can still be corrected. */
export const Available: Story = {
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByRole("button", { name: "Edit URL…" })).toBeEnabled();
  },
};

/** A snapshot exists: no control, and the card says what to do instead. */
export const HasSnapshot: Story = {
  args: { snapshotCount: 2 },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.queryByRole("button", { name: "Edit URL…" })).toBeNull();
    await expect(canvas.getByText(/remove the marketplace and register it again/)).toBeInTheDocument();
  },
};

/** The estate declares it: unavailable, and the reason is on the card. */
export const Declared: Story = {
  parameters: { estate: declared },
  play: async ({ canvasElement }) => {
    const button = within(canvasElement).getByRole("button", { name: "Edit URL…" });
    await expect(button).toBeDisabled();
    await expect(button).toHaveAccessibleDescription(/declared in the estate configuration/);
  },
};

/** The dialog: a credential in the URL keeps it from being saved, and says why. */
export const Dialog: StoryObj<typeof EditMarketplaceUrlDialog> = {
  render: () => <EditMarketplaceUrlDialog name={MARKETPLACE} current={URL} onClose={() => {}} />,
  play: async ({ canvasElement }) => {
    const body = within(canvasElement.ownerDocument.body);
    const dialog = within(await body.findByRole("dialog", { name: `Correct the URL of ${MARKETPLACE}` }));
    const save = dialog.getByRole("button", { name: "Save URL" });
    const field = dialog.getByLabelText("Clone URL");
    await expect(save).toBeEnabled();
    await userEvent.clear(field);
    await userEvent.type(field, "https://user:token@github.com/corp/marketplace.git");
    await expect(save).toBeDisabled();
    await expect(dialog.getByRole("alert")).toHaveTextContent(/Remove the credential/);
  },
};

export const DialogDark: StoryObj<typeof EditMarketplaceUrlDialog> = {
  ...Dialog,
  parameters: { theme: "dark" },
};

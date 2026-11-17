import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import type { MarketplaceView, Snapshot } from "@/api/queries";
import { MarketplaceStatus } from "./marketplace-status";

const snap = (
  id: number,
  state: NonNullable<Snapshot["state"]>,
  createdAt: string,
  extra: Partial<Snapshot> = {},
): Snapshot => ({
  id,
  marketplaceId: 1,
  sha: String(id).repeat(40),
  upstreamSha: String(id).repeat(40),
  state,
  createdAt,
  ...extra,
});

const approved = snap(1, "approved", "2026-08-01T10:00:00Z", {
  decidedAt: "2026-08-02T10:00:00Z",
  decidedBy: "ada",
});

const market = (snapshots: Snapshot[], extra: Partial<MarketplaceView> = {}): MarketplaceView =>
  ({ id: 1, name: "corp-marketplace", snapshots, servedSha: null, ...extra }) as MarketplaceView;

const meta = {
  title: "Marketplace/Status",
  component: MarketplaceStatus,
} satisfies Meta<typeof MarketplaceStatus>;

export default meta;
type Story = StoryObj<typeof meta>;

export const UpToDate: Story = {
  args: { marketplace: market([approved], { servedSha: approved.sha }) },
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByTestId("status-up-to-date")).toBeVisible();
  },
};

export const NewerAwaitingApproval: Story = {
  args: {
    marketplace: market([approved, snap(2, "held", "2026-08-12T10:00:00Z")], {
      servedSha: approved.sha,
    }),
  },
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByTestId("status-who-decides")).toHaveTextContent(
      "ask your gateway administrators",
    );
  },
};

export const IngestFailed: Story = {
  args: {
    marketplace: market([approved], {
      servedSha: approved.sha,
      lastIngestOutcome: "failed",
      lastIngestAt: "2026-08-14T10:00:00Z",
      lastIngestReason: "upstream refused the clone: 403; check the registered credentials",
    }),
  },
};

export const NothingServedNeverApproved: Story = {
  args: { marketplace: market([snap(1, "held", "2026-08-12T10:00:00Z")]) },
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByTestId("status-served")).toHaveTextContent(
      "ever been approved",
    );
  },
};

export const NothingServedWithdrawn: Story = {
  args: { marketplace: market([approved]) },
};

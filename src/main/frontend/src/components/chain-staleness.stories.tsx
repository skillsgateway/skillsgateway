import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { ChainStalenessNotice } from "./vetting-report";

/**
 * What a reviewer is told when the evidence in front of them was produced by a chain the
 * marketplace no longer runs — and, just as deliberately, what they are told when it was not.
 *
 * @Requirements GW_VETTING_0038
 */
const meta = {
  title: "Vetting/ChainStaleness",
  component: ChainStalenessNotice,
  decorators: [
    (Story) => (
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <div className="max-w-2xl p-4">
          <Story />
        </div>
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof ChainStalenessNotice>;

export default meta;
type Story = StoryObj<typeof meta>;

const SUPERSEDED = {
  snapshotId: 1,
  refreshable: true,
  staleness: {
    state: "SUPERSEDED" as const,
    runChain: "secret-scan@1,prompt-injection@1;mode=run-all",
    currentChain: "secret-scan@1,prompt-injection@1;mode=run-all;disabled=[secret-scan]",
  },
};

/** Evidence from the chain in force renders nothing: a marking on every snapshot is noise. */
export const InForce: Story = {
  args: {
    snapshotId: 1,
    refreshable: true,
    staleness: {
      state: "IN_FORCE",
      runChain: "secret-scan@1;mode=run-all",
      currentChain: "secret-scan@1;mode=run-all",
    },
  },
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).queryByText(/different chain/)).not.toBeInTheDocument();
  },
};

/** The case the change exists for: both chains named, and the refresh beside them. */
export const Superseded: Story = {
  args: SUPERSEDED,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText(/different chain than this marketplace runs now/)).toBeVisible();
    await expect(canvas.getByText(/Approval is not blocked by this/)).toBeVisible();
    await expect(
      canvas.getByRole("button", { name: "Re-run the vetting chain on snapshot 1" }),
    ).toBeEnabled();
  },
};

export const SupersededDark: Story = {
  ...Superseded,
  parameters: { theme: "dark" },
};

/** Nothing to refresh — no run — so the fact is stated without a control that would do nothing. */
export const SupersededWithNothingToRefresh: Story = {
  args: { ...SUPERSEDED, refreshable: false },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText(/different chain than this marketplace runs now/)).toBeVisible();
    await expect(
      canvas.queryByRole("button", { name: /Re-run the vetting chain/ }),
    ).not.toBeInTheDocument();
  },
};

/** A run predating the identity stamp: unknown, said as unknown. */
export const Undetermined: Story = {
  args: {
    snapshotId: 1,
    refreshable: true,
    staleness: { state: "UNDETERMINED", runChain: undefined, currentChain: "secret-scan@1;mode=run-all" },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText(/records no chain identity/)).toBeVisible();
    await expect(canvas.queryByText(/different chain/)).not.toBeInTheDocument();
  },
};

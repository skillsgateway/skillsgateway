import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import { NameCollisionNotice } from "./name-collision-notice";

/**
 * What the approve dialog says about plugin names that look like ones another marketplace already
 * serves — and, as deliberately, when it says nothing.
 *
 * @Requirements GW_APPROVAL_0021
 */
const meta = {
  title: "Approval/NameCollisionNotice",
  component: NameCollisionNotice,
  decorators: [
    (Story) => (
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <div className="max-w-2xl p-4">
          <Story />
        </div>
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof NameCollisionNotice>;

export default meta;
type Story = StoryObj<typeof meta>;

const COLLISION = {
  pluginName: "cоde-review",
  location: ".claude-plugin/marketplace.json:6",
  incumbents: [{ marketplace: "acme-tools", snapshotId: 12, pluginName: "code-review" }],
  finding: {
    id: "plugin-name-collision",
    severity: "high" as const,
    location: ".claude-plugin/marketplace.json:6",
    message: "plugin name 'cоde-review' collides with 'code-review' in acme-tools already approved in another marketplace",
  },
  covered: false,
};

/** Nothing collides: nothing is shown. */
export const Clear: Story = {
  args: { snapshotId: 1, check: { enabled: true, inventoryAvailable: true, refused: false, collisions: [] } },
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).queryByText(/already in use/)).not.toBeInTheDocument();
  },
};

/** The rule is switched off: nothing is shown. */
export const Disabled: Story = {
  args: { snapshotId: 1, check: { enabled: false, inventoryAvailable: true, refused: false, collisions: [] } },
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).queryByText(/already in use/)).not.toBeInTheDocument();
  },
};

/** The case the rule exists for: the lookalike, its incumbent, and a waiver on this snapshot only. */
export const Refused: Story = {
  args: {
    snapshotId: 1,
    check: { enabled: true, inventoryAvailable: true, refused: true, collisions: [COLLISION] },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText(/Approval is refused until each is waived/)).toBeVisible();
    await expect(canvas.getByText(/code-review in acme-tools/)).toBeVisible();
    await userEvent.click(canvas.getByRole("button", { name: /Waive name collision/ }));
    const scope = canvas.getByLabelText("Scope");
    await expect(within(scope).getAllByRole("option")).toHaveLength(1);
    await expect(canvas.getByRole("button", { name: /Record waiver for plugin-name-collision/ })).toBeDisabled();
  },
};

/** Accepted: the waiver is named and nothing is left to do. */
export const Waived: Story = {
  args: {
    snapshotId: 1,
    check: {
      enabled: true,
      inventoryAvailable: true,
      refused: false,
      collisions: [
        {
          ...COLLISION,
          covered: true,
          waiver: {
            vetter: "name-collision-gate",
            ruleId: "plugin-name-collision",
            location: ".claude-plugin/marketplace.json:6",
            waiverId: 7,
            approvedBy: "dana",
            expiresAt: "2026-12-31T00:00:00Z",
          },
        },
      ],
    },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText(/waived by dana/)).toBeVisible();
    await expect(canvas.queryByRole("button", { name: /Waive name collision/ })).not.toBeInTheDocument();
  },
};

/** The names could not be read: refused, and said so as an error rather than a collision. */
export const InventoryUnavailable: Story = {
  args: { snapshotId: 1, check: { enabled: true, inventoryAvailable: false, refused: true, collisions: [] } },
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByRole("alert")).toHaveTextContent(/could not be read/);
  },
};

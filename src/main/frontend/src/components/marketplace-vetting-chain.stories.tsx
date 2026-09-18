import { useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import type { ChainSettings } from "@/api/queries";
import { chainSettingsDefault, chainSettingsStopping } from "@/test/msw-handlers";
import { ModeControls, OrderControls } from "./vetting-chain-controls";

const MARKETPLACE = "corp-marketplace";

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });

/**
 * The two chain-level controls, rendered over settings the server has already resolved. The
 * reordering owns its pending arrangement in the card, so the story supplies the same wrapper the
 * card does rather than a stub that could drift from it.
 */
function OrderHarness({ settings }: { settings: ChainSettings }) {
  const [pending, setPending] = useState<string[] | null>(null);
  return (
    <OrderControls
      settings={settings}
      marketplace={MARKETPLACE}
      pending={pending}
      onPending={setPending}
    />
  );
}

const meta = {
  title: "Vetting/ChainSettings",
  component: ModeControls,
  decorators: [
    (Story) => (
      <QueryClientProvider client={client()}>
        <div style={{ maxWidth: 520 }}>
          <Story />
        </div>
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof ModeControls>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The default: every vetter runs, and no administrator has decided otherwise. */
export const ModeRunAll: Story = {
  args: { settings: chainSettingsDefault, marketplace: MARKETPLACE },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const group = within(canvas.getByRole("group", { name: "When a vetter fails" }));
    await expect(group.getByRole("button", { name: "Run every vetter" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    // A default is named as a default, never as a decision somebody made.
    await expect(canvas.getByText(/default — no setting recorded/)).toBeInTheDocument();
    // The cost of the other choice is stated beside the control, not discovered on a snapshot.
    await expect(canvas.getByText(/blocked until it has been run again/)).toBeInTheDocument();
  },
};

/** Stopped early for this marketplace: the pressed segment and the source both say so. */
export const ModeStopAfterFail: Story = {
  args: { settings: chainSettingsStopping, marketplace: MARKETPLACE },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const group = within(canvas.getByRole("group", { name: "When a vetter fails" }));
    await expect(group.getByRole("button", { name: "Stop after a failure" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    await expect(canvas.getByText(/set for this marketplace/)).toBeInTheDocument();
  },
};

/** The same control in the portal's dark theme. */
export const ModeStopAfterFailDark: Story = {
  args: ModeStopAfterFail.args,
  parameters: { theme: "dark" },
};

/** The configured order, with a named movement control per direction on every row. */
export const Order: Story = {
  args: ModeRunAll.args,
  render: () => <OrderHarness settings={chainSettingsDefault} />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const list = canvas.getByRole("list", { name: `Vetter order of ${MARKETPLACE}` });
    await expect(within(list).getAllByRole("listitem")).toHaveLength(3);
    // Every movement is a control with a name of its own, so the reordering is operable by
    // keyboard and announced as what it does rather than as an unlabelled arrow.
    await expect(canvas.getByRole("button", { name: "Move prompt-injection up" })).toBeEnabled();
    // The ends cannot move further out, and say so by being disabled rather than by doing nothing.
    await expect(canvas.getByRole("button", { name: "Move secret-scan up" })).toBeDisabled();
    await expect(canvas.getByRole("button", { name: "Move license-scan down" })).toBeDisabled();
    await expect(canvas.getByRole("button", { name: "Save order" })).toBeDisabled();
  },
};

/** After a movement: the arrangement is proposed, not yet saved, and says so. */
export const OrderReordered: Story = {
  args: ModeRunAll.args,
  render: () => <OrderHarness settings={chainSettingsDefault} />,
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);
    await step("move the second vetter to the front, by its own named control", async () => {
      await userEvent.click(canvas.getByRole("button", { name: "Move prompt-injection up" }));
    });
    const list = canvas.getByRole("list", { name: `Vetter order of ${MARKETPLACE}` });
    const rows = within(list).getAllByRole("listitem");
    await expect(rows[0]).toHaveTextContent("prompt-injection");
    await expect(rows[1]).toHaveTextContent("secret-scan");
    // Nothing is written until the arrangement is saved, and the page says which state it is in.
    await expect(canvas.getByRole("button", { name: "Save order" })).toBeEnabled();
    await expect(canvas.getByText(/Not saved yet/)).toBeInTheDocument();
    // The renumbering a sighted reader watches happen is announced to one who cannot.
    await expect(canvas.getByText("prompt-injection moved to position 1 of 3")).toBeInTheDocument();
    // And focus follows the vetter rather than being dropped when the pressed control disables:
    // at the top of the list, "up" is gone, so "down" is where the keyboard user now is.
    await expect(canvas.getByRole("button", { name: "Move prompt-injection down" })).toHaveFocus();
  },
};

/** An administrator's arrangement, in the portal's dark theme. */
export const OrderDark: Story = {
  args: ModeRunAll.args,
  render: () => <OrderHarness settings={chainSettingsStopping} />,
  parameters: { theme: "dark" },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const rows = within(
      canvas.getByRole("list", { name: `Vetter order of ${MARKETPLACE}` }),
    ).getAllByRole("listitem");
    await expect(rows[0]).toHaveTextContent("prompt-injection");
  },
};

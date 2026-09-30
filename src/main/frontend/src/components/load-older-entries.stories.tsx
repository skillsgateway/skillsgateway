import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";
import { LoadOlderEntries } from "./load-older-entries";

const meta = {
  title: "Audit/LoadOlderEntries",
  component: LoadOlderEntries,
  args: { onLoad: fn() },
} satisfies Meta<typeof LoadOlderEntries>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Older entries exist: the control asks for the next page. */
export const MoreAvailable: Story = {
  args: { loaded: 1000, hasMore: true, loading: false },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByRole("button", { name: "Load older entries" }));
    await expect(args.onLoad).toHaveBeenCalledOnce();
    await expect(canvas.getByText("1000 entries loaded, newest first.")).toBeVisible();
  },
};

/** The next page is on its way; a second press would ask for the same page twice. */
export const Loading: Story = {
  args: { loaded: 1000, hasMore: true, loading: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("button", { name: "Loading…" })).toBeDisabled();
  },
};

/** The oldest entry is loaded: no control, and the reader is told why. */
export const OldestLoaded: Story = {
  args: { loaded: 1240, hasMore: false, loading: false },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.queryByRole("button")).toBeNull();
    await expect(canvas.getByText(/nothing older is recorded/)).toBeVisible();
  },
};

export const MoreAvailableDark: Story = {
  ...MoreAvailable,
  parameters: { theme: "dark" },
};

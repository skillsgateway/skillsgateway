import type { Meta, StoryObj } from "@storybook/react-vite";
import { MemoryRouter } from "react-router-dom";
import { expect, within } from "storybook/test";
import { MarketplaceSections } from "./app-layout";

/**
 * The open marketplace's sections, as the sidebar nests them beneath Marketplaces. Axe runs over
 * the count, which is shown as a chip and announced as words.
 *
 * @Requirements GW_INGEST_0037
 */
const meta = {
  title: "Shell/MarketplaceSections",
  component: MarketplaceSections,
  decorators: [
    (Story, context) => (
      <MemoryRouter initialEntries={[context.parameters.at ?? "/marketplaces/corp-marketplace"]}>
        <nav aria-label="Main" className="w-60 bg-sidebar p-3">
          <Story />
        </nav>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof MarketplaceSections>;

export default meta;
type Story = StoryObj<typeof meta>;

/** On Review with two awaiting: the count is on Review, and Review is the current entry. */
export const ReviewWithAwaiting: Story = {
  args: { name: "corp-marketplace", awaiting: 2 },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const review = canvas.getByRole("link", { name: /Review, 2 awaiting a decision/ });
    await expect(review).toHaveAttribute("aria-current", "page");
    await expect(canvas.getByRole("link", { name: "Settings" })).not.toHaveAttribute("aria-current");
  },
};

/** On Settings with nothing awaiting: no count at all, rather than a zero. */
export const SettingsQuiet: Story = {
  args: { name: "corp-marketplace", awaiting: 0 },
  parameters: { at: "/marketplaces/corp-marketplace/settings" },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("link", { name: "Review" })).not.toHaveTextContent(/\d/);
    await expect(canvas.getByRole("link", { name: "Settings" })).toHaveAttribute("aria-current", "page");
  },
};

export const Dark: Story = {
  args: { name: "a-marketplace-with-a-rather-long-name", awaiting: 12 },
  parameters: { theme: "dark" },
};

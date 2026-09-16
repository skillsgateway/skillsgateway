import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { tokenViews } from "@/test/msw-handlers";
import { IssuedTokenDialog, TokenTable } from "./tokens";

const meta = {
  title: "Tokens/IssuedTokenDialog",
  component: IssuedTokenDialog,
} satisfies Meta<typeof IssuedTokenDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ShowOnce: Story = {
  args: {
    issued: {
      id: 7,
      name: "ci-runner",
      token: "sgw_cleartext_shown_once",
      createdAt: "2026-08-14T10:00:00Z",
    },
    onClose: () => {},
  },
  play: async ({ canvasElement }) => {
    // Dialog renders in a portal attached to the document body.
    const body = within(canvasElement.ownerDocument.body);
    await expect(await body.findByTestId("token-cleartext")).toHaveTextContent(
      "sgw_cleartext_shown_once",
    );
  },
};

/**
 * The two shapes of the Last used column side by side: a credential something authenticated with,
 * and one nothing ever has.
 */
export const LastUsed: StoryObj<typeof TokenTable> = {
  render: () => <TokenTable tokens={tokenViews} onRevoke={() => {}} />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("columnheader", { name: "Last used" })).toBeInTheDocument();
    // The wire value is never discarded, whatever the visible text says.
    await expect(canvas.getByText("never")).toBeInTheDocument();
    const used = canvas.getByRole("row", { name: /ci-runner/ });
    await expect(within(used).getByTitle(/2026-08-14T13:00:00Z/)).toHaveAttribute(
      "datetime",
      "2026-08-14T13:00:00Z",
    );
  },
};

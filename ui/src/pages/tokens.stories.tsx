import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { IssuedTokenDialog } from "./tokens";

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

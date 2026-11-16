import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { scopedTokenViews, tokenViews } from "@/test/msw-handlers";
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

/**
 * Every kind of reach the list has to say in words: every marketplace, one, many (shortened, the
 * whole list one hover or a screen-reader read away), and a token that may also publish.
 */
export const Reach: StoryObj<typeof TokenTable> = {
  render: () => <TokenTable tokens={scopedTokenViews} onRevoke={() => {}} />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      within(canvas.getByRole("row", { name: /unscoped-laptop/ })).getByText("every marketplace"),
    ).toBeInTheDocument();
    await expect(within(canvas.getByRole("row", { name: /ci-many/ })).getByText(/\+2 more/)).toBeInTheDocument();
    await expect(
      within(canvas.getByRole("row", { name: /publisher/ })).getByText("publishes to: tools-hosted"),
    ).toBeInTheDocument();
  },
};

/**
 * The list with the edit control (GW_AUTH_0088): a live token offers Edit reach beside Revoke, a
 * revoked one and an expired one offer neither edit nor, for the revoked, revoke.
 */
export const Editable: StoryObj<typeof TokenTable> = {
  render: () => (
    <TokenTable
      tokens={[
        ...scopedTokenViews.slice(0, 2),
        { ...scopedTokenViews[0]!, id: 31, name: "revoked-laptop", revokedAt: "2026-08-15T10:00:00Z" },
        { ...scopedTokenViews[0]!, id: 32, name: "lapsed-laptop", expiresAt: "2026-08-15T10:00:00Z" },
      ]}
      onRevoke={() => {}}
      onEdit={() => {}}
    />
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("button", { name: "Edit reach of token ci-corp-only" })).toBeInTheDocument();
    await expect(canvas.queryByRole("button", { name: "Edit reach of token revoked-laptop" })).toBeNull();
    await expect(canvas.queryByRole("button", { name: "Edit reach of token lapsed-laptop" })).toBeNull();
  },
};

import type { Meta, StoryObj } from "@storybook/react-vite";
import { ThemeProvider } from "next-themes";
import { MemoryRouter } from "react-router-dom";
import { expect, userEvent, within } from "storybook/test";
import { UserMenuView } from "./app-layout";

/**
 * The signed-in user's menu, one story per persona it has to survive. The play functions open
 * the menu, so axe (violations are errors) inspects the popup rather than the trigger alone.
 *
 * @Requirements GW_AUTH_0044, GW_AUTH_0045, GW_AUTH_0046
 */
const meta = {
  title: "Shell/UserMenu",
  component: UserMenuView,
  decorators: [
    (Story) => (
      <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
        <MemoryRouter>
          <div className="flex justify-end p-4">
            <Story />
          </div>
        </MemoryRouter>
      </ThemeProvider>
    ),
  ],
} satisfies Meta<typeof UserMenuView>;

export default meta;
type Story = StoryObj<typeof meta>;

async function open(canvasElement: HTMLElement) {
  const canvas = within(canvasElement);
  await userEvent.click(await canvas.findByRole("button", { name: /Signed in as/ }));
  return within(canvasElement.ownerDocument.body);
}

/** Roles from every source the gateway reports, each said in the reader's own terms. */
export const RolesFromEverySource: Story = {
  args: {
    me: {
      username: "alice@example.com",
      roles: [
        { role: "admin", marketplace: undefined, source: "config" },
        { role: "approver", marketplace: "corp-marketplace", source: "grant" },
        { role: "auditor", marketplace: undefined, source: "claim" },
      ],
      claimsTruncated: false,
    },
  },
  play: async ({ canvasElement }) => {
    const body = await open(canvasElement);
    await expect(await body.findByText("admin — from configuration")).toBeVisible();
    await expect(body.getByText("approver of corp-marketplace — granted in the portal")).toBeVisible();
    await expect(body.getByText("auditor — from your identity provider")).toBeVisible();
    await expect(body.getByRole("menuitem", { name: "Your tokens" })).toBeVisible();
    await expect(body.getByRole("menuitem", { name: "Sign out" })).toBeVisible();
  },
};

/**
 * The persona the portal must not strand: no role at all. It still reads the portal and still
 * mints its own tokens, and the menu says so rather than showing an empty list.
 */
export const NoRole: Story = {
  args: { me: { username: "reader", roles: [], claimsTruncated: false } },
  play: async ({ canvasElement }) => {
    const body = await open(canvasElement);
    await expect(await body.findByText(/No role\./)).toBeVisible();
    await expect(body.getByRole("menuitem", { name: "Your tokens" })).toBeVisible();
  },
};

/** The identity provider dropped the membership claim: the roles shown are not the whole truth. */
export const TruncatedClaims: Story = {
  args: {
    me: {
      username: "alice@example.com",
      roles: [{ role: "auditor", marketplace: undefined, source: "claim" }],
      claimsTruncated: true,
    },
  },
  play: async ({ canvasElement }) => {
    const body = await open(canvasElement);
    await expect(await body.findByText(/may be incomplete/)).toBeVisible();
  },
};

/** The development escape hatch: an invented principal, and no session that sign-out could end. */
export const DevelopmentEscapeHatch: Story = {
  args: {
    me: {
      username: "dev",
      roles: [{ role: "admin", marketplace: undefined, source: "dev-insecure-auth" }],
      claimsTruncated: false,
    },
  },
  play: async ({ canvasElement }) => {
    const body = await open(canvasElement);
    await expect(await body.findByText(/no session to end/)).toBeVisible();
    await expect(body.queryByRole("menuitem", { name: "Sign out" })).toBeNull();
  },
};

/** The session read failed: an error the reader can see, not a header that quietly loses its identity. */
export const SessionUnreadable: Story = {
  args: { isError: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByRole("alert")).toHaveTextContent("Could not read your session");
  },
};

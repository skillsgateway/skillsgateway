import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import { SetupWizard } from "./setup-wizard";

const meta = {
  title: "Marketplaces/SetupWizard",
  component: SetupWizard,
  decorators: [
    (Story) => (
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <Story />
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof SetupWizard>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * A marketplace that is serving: the credential command leads as the one labelled copy control,
 * and the lifetime arrives bounded rather than permanent.
 */
export const Serving: Story = {
  args: { marketplace: "corp-marketplace", serving: true, onClose: () => {} },
  play: async ({ canvasElement }) => {
    // The dialog renders in a portal attached to the document body.
    const body = within(canvasElement.ownerDocument.body);
    await expect(await body.findByRole("button", { name: "Copy credential command" })).toBeVisible();
    // The credential step configures a helper and reads the token at a prompt: no token in it.
    await expect(body.getByTestId("wizard-credential-config")).toHaveTextContent(".helper' store");
    await expect(body.getByTestId("wizard-credential-config")).toHaveTextContent("read -rs SGW_TOKEN");
    await expect(body.getByTestId("wizard-token-reach")).toHaveTextContent(
      "not limited to 'corp-marketplace'",
    );
    await expect(
      within(body.getByRole("group", { name: "Expires" })).getByRole("button", { name: "30 days" }),
    ).toHaveAttribute("aria-pressed", "true");
    await expect(body.queryByTestId("setup-held-notice")).toBeNull();
  },
};

/**
 * The same wizard in the portal's dark theme.
 *
 * The lifetime control is a themed button group rather than a native `<select>` precisely so
 * that this story is a fair picture of it: a browser paints a select's open list with its own
 * chrome, and nothing in the theme reaches inside it.
 */
export const ServingDark: Story = {
  args: { marketplace: "corp-marketplace", serving: true, onClose: () => {} },
  parameters: { theme: "dark" },
  play: async ({ canvasElement }) => {
    const body = within(canvasElement.ownerDocument.body);
    await expect(
      within(await body.findByRole("group", { name: "Expires" })).getByRole("button", {
        name: "No expiry",
      }),
    ).toHaveAttribute("aria-pressed", "false");
  },
};

/**
 * A marketplace whose only snapshot is still held: the commands are correct but nothing answers
 * them yet, and the wizard says so in the terms the consumer's client will use.
 */
export const SnapshotStillHeld: Story = {
  args: { marketplace: "corp-marketplace", serving: false, onClose: () => {} },
  play: async ({ canvasElement }) => {
    const body = within(canvasElement.ownerDocument.body);
    await expect(await body.findByTestId("setup-held-notice")).toHaveTextContent("404");
  },
};

/** The fixture cleartext the stubbed token endpoint issues: throwaway, never a real credential. */
const STORY_TOKEN = "sgw_story_fixture_not_a_real_token";

/**
 * A token minted inside the wizard: shown once in its own field, to paste at the credential
 * command's prompt, while the commands themselves stay exactly as they were.
 *
 * Storybook has no API, so the one call the wizard makes is answered here and restored after.
 */
export const TokenMinted: Story = {
  args: { marketplace: "corp-marketplace", serving: true, onClose: () => {} },
  beforeEach: () => {
    const original = window.fetch;
    window.fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url.endsWith("/api/v1/tokens") && init?.method === "POST") {
        return new Response(
          JSON.stringify({
            id: 1,
            name: "corp-marketplace-client",
            token: STORY_TOKEN,
            createdAt: "2026-10-30T09:00:00Z",
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        );
      }
      return original(input, init);
    };
    return () => {
      window.fetch = original;
    };
  },
  play: async ({ canvasElement }) => {
    const body = within(canvasElement.ownerDocument.body);
    const before = (await body.findByTestId("wizard-credential-config")).textContent;
    await userEvent.click(body.getByRole("button", { name: "Create token" }));
    await expect(await body.findByTestId("wizard-token-value")).toHaveTextContent(STORY_TOKEN);
    await expect(body.getByRole("button", { name: "Copy token" })).toBeVisible();
    await expect(body.getByTestId("wizard-credential-config").textContent).toBe(before);
    await expect(body.getByTestId("wizard-credential-config")).not.toHaveTextContent(STORY_TOKEN);
  },
};

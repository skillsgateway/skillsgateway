import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fireEvent, userEvent, within } from "storybook/test";
import type { AutoApprovalView } from "@/api/queries";
import { AutoApproval, MAX_DAYS } from "./auto-approval";

const MARKETPLACE = "corp-marketplace";

/** Relative to now, so a lapsed setting stays lapsed and a live one stays live whenever this runs. */
function inDays(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString();
}

function dateInput(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

const off: AutoApprovalView = { enabled: false, allowedBy: "none", mode: "on" };

const internal: AutoApprovalView = {
  enabled: true,
  allowedBy: "internal-source",
  mode: "on",
  setBy: "alice",
  setAt: inDays(-5),
  reason: "our own skills repository",
  expiresAt: inDays(60),
};

const byExemption: AutoApprovalView = {
  ...internal,
  allowedBy: "exemption",
  reason: "vendor skills, audited quarterly",
  exemption: {
    id: 7,
    reason: "vendor repository we audit quarterly",
    createdBy: "alice",
    createdAt: inDays(-5),
    expiresAt: inDays(30),
  },
};

/** The setting is seeded rather than fetched: the story shows what the card does with it. */
function withSetting(view: AutoApprovalView) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(["auto-approval", MARKETPLACE], view);
  return client;
}

const meta = {
  title: "Marketplace/AutoApproval",
  component: AutoApproval,
  args: { marketplace: MARKETPLACE, canAdminister: true },
  parameters: { autoApproval: off },
  decorators: [
    (Story, context) => (
      <QueryClientProvider client={withSetting(context.parameters.autoApproval as AutoApprovalView)}>
        <div style={{ maxWidth: 720 }}>
          <Story />
        </div>
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof AutoApproval>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing set: every snapshot waits for a person, and an administrator sees the form. */
export const Off: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Not approved automatically")).toBeInTheDocument();
    await expect(canvas.getByRole("button", { name: "Save automatic approval" })).toBeEnabled();
  },
};

/** On for an internal source, with the gateway on: published with no person. */
export const OnInternal: Story = {
  parameters: { autoApproval: internal },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Approved automatically")).toBeInTheDocument();
    await expect(canvas.getByText("Internal source")).toBeInTheDocument();
    await expect(canvas.getByText("our own skills repository")).toBeInTheDocument();
  },
};

/** On because of an administrator exemption, which an administrator can revoke after confirming. */
export const OnByExemption: Story = {
  parameters: { autoApproval: byExemption },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Administrator exemption")).toBeInTheDocument();
    await userEvent.click(canvas.getByRole("button", { name: "Revoke exemption…" }));
    const body = within(canvasElement.ownerDocument.body);
    const dialog = within(await body.findByRole("dialog", { name: `Revoke the exemption on ${MARKETPLACE}` }));
    await expect(dialog.getByRole("button", { name: "Revoke exemption" })).toBeEnabled();
    await userEvent.click(dialog.getByRole("button", { name: "Cancel" }));
  },
};

/** Turned on by an administrator with a stake in the marketplace: not in effect until another does. */
export const AwaitingSecondAdministrator: Story = {
  parameters: { autoApproval: { ...internal, allowedBy: "awaiting-second-administrator" } },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Not approved automatically")).toBeInTheDocument();
    await expect(canvas.getByText(/takes effect once another administrator turns it on/)).toBeInTheDocument();
  },
};

/** Past its date: off again, marked lapsed, and the card says how to resume. */
export const Lapsed: Story = {
  parameters: { autoApproval: { ...internal, allowedBy: "none", expiresAt: inDays(-1) } },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getAllByText("Lapsed").length).toBeGreaterThan(0);
    await expect(canvas.getByText(/Renew it to resume/)).toBeInTheDocument();
  },
};

/** The gateway in dry run: evaluated and recorded, nothing published. */
export const DryRun: Story = {
  parameters: { autoApproval: { ...internal, mode: "dry-run" } },
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByText("Dry run: recorded, not published")).toBeInTheDocument();
  },
};

/** Anyone else sees the setting and no control. */
export const NonAdministrator: Story = {
  args: { canAdminister: false },
  parameters: { autoApproval: byExemption },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Approved automatically")).toBeInTheDocument();
    await expect(canvas.queryByRole("button", { name: "Save automatic approval" })).toBeNull();
    await expect(canvas.queryByRole("button", { name: "Revoke exemption…" })).toBeNull();
  },
};

/** Turning it on needs a reason and a date in range, and the exemption its own, before it can be saved. */
export const SavedOnlyWhenValid: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByRole("button", { name: "On" }));
    const save = canvas.getByRole("button", { name: "Save automatic approval" });
    await expect(save).toBeDisabled();
    await userEvent.type(canvas.getByLabelText("Reason"), "   ");
    fireEvent.change(canvas.getByLabelText("Lapses on"), { target: { value: dateInput(30) } });
    await expect(save).toBeDisabled();
    await userEvent.type(canvas.getByLabelText("Reason"), "our own skills");
    await expect(save).toBeEnabled();
    await expect(canvas.getByLabelText("Lapses on")).toHaveAttribute("max", dateInput(MAX_DAYS));
    fireEvent.change(canvas.getByLabelText("Lapses on"), { target: { value: dateInput(MAX_DAYS + 1) } });
    await expect(save).toBeDisabled();
    fireEvent.change(canvas.getByLabelText("Lapses on"), { target: { value: dateInput(30) } });
    await userEvent.click(canvas.getByRole("checkbox", { name: "Add an exemption" }));
    await expect(save).toBeDisabled();
    await userEvent.type(canvas.getByLabelText("Exemption reason"), "vendor repository");
    fireEvent.change(canvas.getByLabelText("Exemption lapses on"), { target: { value: dateInput(30) } });
    await expect(save).toBeEnabled();
  },
};

/**
 * A refusal is the server's to make (here, not an internal source and no exemption): shown as it
 * states it, with its next step. Storybook has no API, so the one call is answered here and restored after.
 */
export const RefusedSave: Story = {
  beforeEach: () => {
    const original = window.fetch;
    window.fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url.endsWith(`/marketplaces/${MARKETPLACE}/auto-approval`) && init?.method === "PUT") {
        return new Response(
          JSON.stringify({
            status: 409,
            detail: `${MARKETPLACE} is not an internal source, so automatic approval needs an exemption`,
            nextStep: "turn it on again with an exemption, or add the URL prefix to the internal sources",
          }),
          { status: 409, headers: { "Content-Type": "application/problem+json" } },
        );
      }
      return original(input, init);
    };
    return () => {
      window.fetch = original;
    };
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByRole("button", { name: "On" }));
    await userEvent.type(canvas.getByLabelText("Reason"), "vendor skills");
    fireEvent.change(canvas.getByLabelText("Lapses on"), { target: { value: dateInput(30) } });
    await userEvent.click(canvas.getByRole("button", { name: "Save automatic approval" }));
    const alert = await canvas.findByRole("alert");
    await expect(alert).toHaveTextContent(/needs an exemption/);
    await expect(alert).toHaveTextContent(/Next step: turn it on again with an exemption/);
  },
};

/** The exemption state in the dark theme. */
export const Dark: Story = {
  parameters: { autoApproval: byExemption, theme: "dark" },
};

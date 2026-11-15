import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fireEvent, userEvent, within } from "storybook/test";
import type { PostureView } from "@/api/queries";
import { ApprovalPosture, MAX_DAYS } from "./approval-posture";

const MARKETPLACE = "corp-marketplace";

/** Relative to now, so a lapsed posture stays lapsed and a live one stays live whenever this runs. */
function inDays(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString();
}

function dateInput(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

const manual: PostureView = { posture: "manual", basis: "none", mode: "on" };

const internal: PostureView = {
  posture: "auto",
  basis: "internal-prefix",
  mode: "on",
  setBy: "alice",
  setAt: inDays(-5),
  reason: "our own skills repository",
  expiresAt: inDays(60),
};

const byOverride: PostureView = {
  ...internal,
  basis: "override",
  reason: "vendor skills, audited quarterly",
  override: {
    id: 7,
    reason: "vendor repository we audit quarterly",
    createdBy: "alice",
    createdAt: inDays(-5),
    expiresAt: inDays(30),
  },
};

/** The posture is seeded rather than fetched: the story shows what the card does with it. */
function withPosture(view: PostureView) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(["approval-posture", MARKETPLACE], view);
  return client;
}

const meta = {
  title: "Marketplace/ApprovalPosture",
  component: ApprovalPosture,
  args: { marketplace: MARKETPLACE, canAdminister: true },
  parameters: { posture: manual },
  decorators: [
    (Story, context) => (
      <QueryClientProvider client={withPosture(context.parameters.posture as PostureView)}>
        <div style={{ maxWidth: 720 }}>
          <Story />
        </div>
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof ApprovalPosture>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing set: every snapshot waits for a person, and an administrator sees the form. */
export const Manual: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Not approved automatically")).toBeInTheDocument();
    await expect(canvas.getByRole("button", { name: "Save posture" })).toBeEnabled();
  },
};

/** Automatic on an internal source, with the deployment on: published with no person. */
export const AutomaticInternal: Story = {
  parameters: { posture: internal },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Approved automatically")).toBeInTheDocument();
    await expect(canvas.getByText(/Internal source: under a configured URL prefix/)).toBeInTheDocument();
    await expect(canvas.getByText("our own skills repository")).toBeInTheDocument();
  },
};

/** Automatic resting on an override, which an administrator can revoke after confirming. */
export const AutomaticByOverride: Story = {
  parameters: { posture: byOverride },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Administrator override")).toBeInTheDocument();
    await userEvent.click(canvas.getByRole("button", { name: "Revoke override…" }));
    const body = within(canvasElement.ownerDocument.body);
    const dialog = within(await body.findByRole("dialog", { name: `Revoke the override on ${MARKETPLACE}` }));
    await expect(dialog.getByRole("button", { name: "Revoke override" })).toBeEnabled();
    await userEvent.click(dialog.getByRole("button", { name: "Cancel" }));
  },
};

/** Set by an administrator with a stake in the marketplace: not in effect until another sets it. */
export const AwaitingSecondAdministrator: Story = {
  parameters: { posture: { ...internal, basis: "awaiting-second-administrator" } },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Not approved automatically")).toBeInTheDocument();
    await expect(canvas.getByText(/takes effect once another administrator sets it/)).toBeInTheDocument();
  },
};

/** Past its date: manual again, marked lapsed, and the card says how to resume. */
export const Lapsed: Story = {
  parameters: { posture: { ...internal, basis: "none", expiresAt: inDays(-1) } },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getAllByText("Lapsed").length).toBeGreaterThan(0);
    await expect(canvas.getByText(/Renew it to resume/)).toBeInTheDocument();
  },
};

/** The deployment in shadow: evaluated and recorded, nothing published. */
export const Shadow: Story = {
  parameters: { posture: { ...internal, mode: "shadow" } },
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByText("Shadow: recorded, not published")).toBeInTheDocument();
  },
};

/** Anyone else sees the posture and no control. */
export const NonAdministrator: Story = {
  args: { canAdminister: false },
  parameters: { posture: byOverride },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Approved automatically")).toBeInTheDocument();
    await expect(canvas.queryByRole("button", { name: "Save posture" })).toBeNull();
    await expect(canvas.queryByRole("button", { name: "Revoke override…" })).toBeNull();
  },
};

/** Automatic needs a reason and a date in range, and the override its own, before it can be saved. */
export const SavedOnlyWhenValid: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByRole("button", { name: "Automatic" }));
    const save = canvas.getByRole("button", { name: "Save posture" });
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
    await userEvent.click(canvas.getByRole("checkbox", { name: "Add an override" }));
    await expect(save).toBeDisabled();
    await userEvent.type(canvas.getByLabelText("Override reason"), "vendor repository");
    fireEvent.change(canvas.getByLabelText("Override lapses on"), { target: { value: dateInput(30) } });
    await expect(save).toBeEnabled();
  },
};

/**
 * A refusal is the server's to make (here, no internal basis and no override): shown as it states
 * it, with its next step. Storybook has no API, so the one call is answered here and restored after.
 */
export const RefusedSave: Story = {
  beforeEach: () => {
    const original = window.fetch;
    window.fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url.endsWith(`/marketplaces/${MARKETPLACE}/approval-posture`) && init?.method === "PUT") {
        return new Response(
          JSON.stringify({
            status: 409,
            detail: `${MARKETPLACE} is not an internal source, so an automatic posture needs an override`,
            nextStep: "set the posture again with an override, or add the URL prefix to the internal sources",
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
    await userEvent.click(canvas.getByRole("button", { name: "Automatic" }));
    await userEvent.type(canvas.getByLabelText("Reason"), "vendor skills");
    fireEvent.change(canvas.getByLabelText("Lapses on"), { target: { value: dateInput(30) } });
    await userEvent.click(canvas.getByRole("button", { name: "Save posture" }));
    const alert = await canvas.findByRole("alert");
    await expect(alert).toHaveTextContent(/needs an override/);
    await expect(alert).toHaveTextContent(/Next step: set the posture again with an override/);
  },
};

/** The override state in the dark theme. */
export const Dark: Story = {
  parameters: { posture: byOverride, theme: "dark" },
};

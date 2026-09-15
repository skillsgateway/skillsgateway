import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import type { WaiverSuppression } from "@/api/queries";
import {
  blockedVetting,
  clearVetting,
  disabledAndPendingVetting,
  marketplaceChain,
  waivedVetting,
} from "@/test/msw-handlers";
import { marketplaceFlow, snapshotFlow } from "@/lib/vetting-flow";
import { VettingFlow } from "./vetting-flow";

/** The report's own suppression index, so a waived story draws the acceptance the report draws. */
function suppressionsOf(view: typeof waivedVetting) {
  return new Map<string, WaiverSuppression>(
    (view.suppressed ?? []).map((suppression) => [
      `${suppression.connector ?? ""}|${suppression.ruleId ?? ""}|${suppression.location ?? ""}`,
      suppression,
    ]),
  );
}

const meta = {
  title: "Vetting/VettingFlow",
  component: VettingFlow,
} satisfies Meta<typeof VettingFlow>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Every connector passed: the gate is open and nothing is waiting on a person to accept a risk. */
export const Clear: Story = {
  args: { label: "Vetting chain of snapshot 1", nodes: snapshotFlow(clearVetting) },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("list", { name: "Vetting chain of snapshot 1" })).toBeInTheDocument();
    await expect(canvas.getByText("clear")).toBeInTheDocument();
    await expect(canvas.getByText("open")).toBeInTheDocument();
  },
};

/** One connector failed. The state is a word, not only a colour, and the gate reads closed. */
export const Blocked: Story = {
  args: { label: "Vetting chain of snapshot 1", nodes: snapshotFlow(blockedVetting) },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("fail")).toBeInTheDocument();
    await expect(canvas.getByText("blocked")).toBeInTheDocument();
    await expect(canvas.getByText("closed")).toBeInTheDocument();
  },
};

/** Cleared only because a finding was accepted: the connector still reads as having failed. */
export const WithWaivers: Story = {
  args: {
    label: "Vetting chain of snapshot 1",
    nodes: snapshotFlow(waivedVetting),
    suppressions: suppressionsOf(waivedVetting),
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("clear with waivers")).toBeInTheDocument();
    await expect(canvas.getByText("1 waived")).toBeInTheDocument();
    await expect(canvas.getByText("fail")).toBeInTheDocument();
  },
};

/** A connector an administrator switched off, and an external one that has not answered yet. */
export const SkippedAndPending: Story = {
  args: {
    label: "Vetting chain of snapshot 1",
    nodes: snapshotFlow(disabledAndPendingVetting),
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("skipped")).toBeInTheDocument();
    await expect(canvas.getByText("pending")).toBeInTheDocument();
    await expect(canvas.getByText("external")).toBeInTheDocument();
  },
};

/** A node opens its own evidence: the findings behind the verdict, and what the connector is. */
export const NodeDetail: Story = {
  args: { label: "Vetting chain of snapshot 1", nodes: snapshotFlow(blockedVetting) },
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);
    await step("open the failing connector's node", async () => {
      await userEvent.click(
        canvas.getByRole("button", { name: "Vetting chain of snapshot 1: secret-scan, fail" }),
      );
    });
    const dialog = within(await within(document.body).findByRole("dialog"));
    await expect(dialog.getByText("aws-access-key-id")).toBeInTheDocument();
    await expect(dialog.getByText("Regex and entropy rules over text files.")).toBeInTheDocument();
  },
};

/** The same drawing without verdicts: what runs for a marketplace, and which setting decided it. */
export const MarketplaceChain: Story = {
  args: {
    label: "Vetting chain of corp-marketplace",
    nodes: marketplaceFlow(marketplaceChain),
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("disabled")).toBeInTheDocument();
    await expect(canvas.getAllByText("enabled")).toHaveLength(2);
    await userEvent.click(
      canvas.getByRole("button", { name: "Vetting chain of corp-marketplace: license-scan, enabled" }),
    );
    const dialog = within(await within(document.body).findByRole("dialog"));
    await expect(dialog.getByText("default — no setting recorded")).toBeInTheDocument();
  },
};

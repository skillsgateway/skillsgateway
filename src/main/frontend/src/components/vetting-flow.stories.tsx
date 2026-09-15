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
import { marketplaceFlow, marketplaceHeadline, snapshotFlow, snapshotHeadline } from "@/lib/vetting-flow";
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

const SNAPSHOT_LABEL = "Vetting chain of snapshot 1";
const MARKETPLACE_LABEL = "Vetting chain of corp-marketplace";

function snapshotStory(view: typeof blockedVetting) {
  const nodes = snapshotFlow(view);
  return { label: SNAPSHOT_LABEL, headline: snapshotHeadline(nodes), nodes };
}

/** Every connector passed: the gate is open and nothing is waiting on a person to accept a risk. */
export const Clear: Story = {
  args: snapshotStory(clearVetting),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("list", { name: SNAPSHOT_LABEL })).toBeInTheDocument();
    // The answer is stated once, above the drawing, before anything is clicked.
    await expect(canvas.getByText("Clear")).toBeInTheDocument();
    await expect(canvas.getByText("· 2 connectors, 0 findings")).toBeInTheDocument();
    await expect(canvas.getByRole("button", { name: `${SNAPSHOT_LABEL}: Approval, open` })).toBeInTheDocument();
  },
};

/** One connector failed. The headline names the step; the gate reads closed. */
export const Blocked: Story = {
  args: snapshotStory(blockedVetting),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Blocked at step 1")).toBeInTheDocument();
    await expect(canvas.getByText("· secret-scan found 1 critical finding")).toBeInTheDocument();
    // Every state is a word on its own node, not only the headline and not only a colour.
    await expect(canvas.getByRole("button", { name: `${SNAPSHOT_LABEL}: secret-scan, fail` })).toBeInTheDocument();
    await expect(canvas.getByRole("button", { name: `${SNAPSHOT_LABEL}: Outcome, blocked` })).toBeInTheDocument();
    await expect(canvas.getByRole("button", { name: `${SNAPSHOT_LABEL}: Approval, closed` })).toBeInTheDocument();
    // Ordered stages, not an undifferentiated row.
    await expect(canvas.getByText("Step 1")).toBeInTheDocument();
    await expect(canvas.getByText("Result")).toBeInTheDocument();
  },
};

/** Cleared only because a finding was accepted: the connector still reads as having failed. */
export const WithWaivers: Story = {
  args: {
    ...snapshotStory(waivedVetting),
    suppressions: suppressionsOf(waivedVetting),
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Clear with waivers")).toBeInTheDocument();
    await expect(canvas.getByText("· 1 finding accepted")).toBeInTheDocument();
    await expect(canvas.getByText("1 waived")).toBeInTheDocument();
    // Accepted risk is never redrawn as a clean result.
    await expect(canvas.getByRole("button", { name: `${SNAPSHOT_LABEL}: secret-scan, fail` })).toBeInTheDocument();
  },
};

/** A connector an administrator switched off, and an external one that has not answered yet. */
export const SkippedAndPending: Story = {
  args: snapshotStory(disabledAndPendingVetting),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Blocked at step 3")).toBeInTheDocument();
    await expect(canvas.getByText("· corp-llm-reviewer has not answered yet")).toBeInTheDocument();
    await expect(canvas.getByText("skipped")).toBeInTheDocument();
    await expect(canvas.getByText("pending")).toBeInTheDocument();
    await expect(canvas.getByText("external")).toBeInTheDocument();
  },
};

/** A node opens its own evidence: the findings behind the verdict, and what the connector is. */
export const NodeDetail: Story = {
  args: snapshotStory(blockedVetting),
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);
    await step("open the failing connector's node", async () => {
      await userEvent.click(canvas.getByRole("button", { name: `${SNAPSHOT_LABEL}: secret-scan, fail` }));
    });
    const dialog = within(await within(document.body).findByRole("dialog"));
    await expect(dialog.getByText("aws-access-key-id")).toBeInTheDocument();
    await expect(dialog.getByText("Regex and entropy rules over text files.")).toBeInTheDocument();
  },
};

/** The same drawing without verdicts: what runs for a marketplace, and which setting decided it. */
export const MarketplaceChain: Story = {
  args: {
    label: MARKETPLACE_LABEL,
    headline: marketplaceHeadline(marketplaceFlow(marketplaceChain)),
    nodes: marketplaceFlow(marketplaceChain),
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("2 of 3 connectors run")).toBeInTheDocument();
    await expect(canvas.getByText("· secret-scan off for this marketplace")).toBeInTheDocument();
    // Each node carries where its state came from, so a default is not read as a decision.
    await expect(canvas.getByText("this marketplace")).toBeInTheDocument();
    await expect(canvas.getByText("global")).toBeInTheDocument();
    await expect(canvas.getByText("default")).toBeInTheDocument();
    await expect(canvas.getByText("off by alice")).toBeInTheDocument();
  },
};

/** Opening a configured connector: where its state came from, and the switch itself. */
export const MarketplaceChainDetail: Story = {
  args: MarketplaceChain.args,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      canvas.getByRole("button", { name: `${MARKETPLACE_LABEL}: license-scan, enabled` }),
    );
    const dialog = within(await within(document.body).findByRole("dialog"));
    await expect(dialog.getByText("default — no setting recorded")).toBeInTheDocument();
  },
};

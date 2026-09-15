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
      `${suppression.vetter ?? ""}|${suppression.ruleId ?? ""}|${suppression.location ?? ""}`,
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

/** Every vetter passed: the gate is open and nothing is waiting on a person to accept a risk. */
export const Clear: Story = {
  args: snapshotStory(clearVetting),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("list", { name: SNAPSHOT_LABEL })).toBeInTheDocument();
    // The answer is stated once, above the drawing, before anything is clicked.
    await expect(canvas.getByText("Clear")).toBeInTheDocument();
    await expect(canvas.getByText("· 2 vetters, 0 findings")).toBeInTheDocument();
    await expect(canvas.getByRole("button", { name: `${SNAPSHOT_LABEL}: Approval, open` })).toBeInTheDocument();
  },
};

/** One vetter failed. The headline names the step; the gate reads closed. */
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

/** Cleared only because a finding was accepted: the vetter still reads as having failed. */
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

/** A vetter an administrator switched off, and an external one that has not answered yet. */
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

/** A node opens its own evidence: the findings behind the verdict, and what the vetter is. */
export const NodeDetail: Story = {
  args: snapshotStory(blockedVetting),
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);
    await step("open the failing vetter's node", async () => {
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
    await expect(canvas.getByText("2 of 3 vetters run")).toBeInTheDocument();
    await expect(canvas.getByText("· secret-scan off for this marketplace")).toBeInTheDocument();
    // Each node carries where its state came from, so a default is not read as a decision.
    await expect(canvas.getByText("this marketplace")).toBeInTheDocument();
    await expect(canvas.getByText("global")).toBeInTheDocument();
    await expect(canvas.getByText("default")).toBeInTheDocument();
    await expect(canvas.getByText("off by alice")).toBeInTheDocument();
  },
};

/** Opening a configured vetter: where its state came from, and the switch itself. */
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

/**
 * The same clean run, four vetters long, so the flow draws its full seven nodes: Source, four
 * steps, Result and Gate.
 */
const fourVetterVetting: typeof clearVetting = {
  ...clearVetting,
  run: {
    ...clearVetting.run!,
    verdicts: [
      ...clearVetting.run!.verdicts!,
      { verdictId: 20, vetter: "license-scan", position: 2, state: "PASS", findings: [] },
      { verdictId: 21, vetter: "corp-llm-reviewer", position: 3, state: "PASS", findings: [] },
    ],
  },
  vetters: [
    ...clearVetting.vetters!,
    { name: "license-scan", order: 300, description: "SPDX headers.", version: "1" },
    {
      name: "corp-llm-reviewer",
      order: 400,
      description: "An operator-configured external reviewer.",
      version: "2026-09-01",
      external: true,
    },
  ],
};

/**
 * Seven nodes in a 720px column: the flow stays one unbroken left-to-right line and scrolls
 * sideways instead of wrapping. A wrapped row left the Gate stranded on a second line, detached
 * from the Result it follows, which is the whole reason the row does not wrap.
 */
export const SevenNodesNarrow: Story = {
  args: snapshotStory(fourVetterVetting),
  decorators: [
    (Story) => (
      <div style={{ width: 720 }}>
        <Story />
      </div>
    ),
  ],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const list = canvas.getByRole("list", { name: SNAPSHOT_LABEL });
    await expect(within(list).getAllByRole("listitem")).toHaveLength(7);

    // The class contract, not the geometry: this runner compiles no Tailwind, so the rendered
    // widths here are meaningless. What it can hold is that the row is a single non-wrapping
    // line inside a container that scrolls — which is what keeps the gate beside the result.
    await expect(list.className).toContain("flex");
    await expect(list.className).not.toContain("wrap");
    await expect(list.parentElement!.className).toContain("overflow-x-auto");

    // A node that has to narrow still says its whole name to a pointer and to assistive tech.
    await expect(canvas.getByTitle("corp-llm-reviewer")).toBeInTheDocument();

    // The gate is still the last node of that line, and still reachable.
    await expect(
      canvas.getByRole("button", { name: `${SNAPSHOT_LABEL}: Approval, open` }),
    ).toBeInTheDocument();
  },
};

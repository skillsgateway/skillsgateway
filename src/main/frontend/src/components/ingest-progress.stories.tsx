import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { IngestProgress } from "./ingest-progress";

const NOW = Date.parse("2026-10-05T12:00:00Z");
const ago = (seconds: number) => new Date(NOW - seconds * 1000).toISOString();

/**
 * An ingest in progress on the marketplace header (GW_INGEST_0068), at each stage, and one whose
 * gateway instance stopped (GW_INGEST_0069). The clock is fixed so the elapsed time is stable.
 *
 * @Requirements GW_INGEST_0068, GW_INGEST_0069
 */
const meta = {
  title: "Marketplace/IngestProgress",
  component: IngestProgress,
  args: { now: NOW, running: { stage: "queued", startedAt: ago(2), interrupted: false } },
  decorators: [
    (Story) => (
      <div style={{ maxWidth: 720 }}>
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof IngestProgress>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Queued: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Queued").closest("li")).toHaveAttribute("aria-current", "step");
    await expect(canvas.getByTestId("ingest-elapsed")).toHaveTextContent("2s");
  },
};

export const Fetching: Story = {
  args: { running: { stage: "fetching", startedAt: ago(14), interrupted: false } },
};

export const EvaluatingManifest: Story = {
  args: { running: { stage: "evaluating-manifest", startedAt: ago(48), interrupted: false } },
};

/** The longest stage on a large marketplace: the stages before it are done, and the time is in minutes. */
export const Vetting: Story = {
  args: { running: { stage: "vetting", startedAt: ago(192), interrupted: false } },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Vetting").closest("li")).toHaveAttribute("aria-current", "step");
    await expect(canvas.getByText("Fetching").closest("li")).toHaveTextContent("(done)");
    await expect(canvas.getByTestId("ingest-elapsed")).toHaveTextContent("3m 12s");
    await expect(canvas.getByText("Ingest stage: Vetting")).toBeInTheDocument();
  },
};

export const Interrupted: Story = {
  args: { running: { stage: "fetching", startedAt: ago(600), interrupted: true } },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("alert")).toHaveTextContent(/interrupted while fetching/);
    await expect(canvas.queryByRole("list", { name: "Ingest stages" })).not.toBeInTheDocument();
  },
};

export const VettingDark: Story = {
  args: { running: { stage: "vetting", startedAt: ago(192), interrupted: false } },
  parameters: { theme: "dark" },
};

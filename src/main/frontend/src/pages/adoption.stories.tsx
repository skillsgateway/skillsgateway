import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { adoptionEntry, presenceReport, staleIdentities } from "@/test/msw-handlers";
import { AdoptionMarketplaceCard, PresenceTable, StalenessTable } from "./adoption";

const meta = {
  title: "Adoption/AdoptionMarketplaceCard",
  component: AdoptionMarketplaceCard,
} satisfies Meta<typeof AdoptionMarketplaceCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A serving marketplace with the tip and one superseded SHA in its breakdown. */
export const Serving: Story = {
  args: { entry: adoptionEntry },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("corp-marketplace")).toBeInTheDocument();
    await expect(canvas.getByText("current")).toBeInTheDocument();
    await expect(canvas.getByText("superseded")).toBeInTheDocument();
  },
};

/** A marketplace fetched during the window but no longer serving anything. */
export const NotServing: Story = {
  args: {
    entry: {
      ...adoptionEntry,
      marketplace: "retired-marketplace",
      servedSha: undefined,
      snapshots: (adoptionEntry.snapshots ?? []).map((snapshot) => ({
        ...snapshot,
        current: false,
      })),
    },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("not serving")).toBeInTheDocument();
  },
};

/** The staleness table with both flavors: behind the tip, and holding retracted content. */
export const Staleness: StoryObj<typeof StalenessTable> = {
  render: () => <StalenessTable entries={staleIdentities} />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("team-payments")).toBeInTheDocument();
    await expect(canvas.getByText("not serving")).toBeInTheDocument();
  },
};

/** Skill presence with its statement above the table and one unresolvable snapshot. */
export const Presence: StoryObj<typeof PresenceTable> = {
  render: () => <PresenceTable report={presenceReport} />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText(/Presence, not invocation/)).toBeInTheDocument();
    await expect(canvas.getByText("code-review")).toBeInTheDocument();
    await expect(canvas.getByText("release-notes")).toBeInTheDocument();
    await expect(canvas.getByText("Content not resolvable")).toBeInTheDocument();
  },
};

/** Presence with nothing unresolvable: no note is shown. */
export const PresenceAllResolved: StoryObj<typeof PresenceTable> = {
  render: () => <PresenceTable report={{ ...presenceReport, unresolved: [] }} />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.queryByText("Content not resolvable")).not.toBeInTheDocument();
  },
};

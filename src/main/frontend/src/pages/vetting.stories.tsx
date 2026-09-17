import { MemoryRouter } from "react-router-dom";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import { bulkPartialFailure, chainOverrides, vetterToggles } from "@/test/msw-handlers";
import { overridesOf } from "@/lib/vetting-overrides";
import type { MarketplaceView } from "@/api/queries";
import { BulkEditor, OverridesTable, VettingRefusal } from "./vetting";

const MARKETPLACES: MarketplaceView[] = [
  { id: 1, name: "corp-marketplace" },
  { id: 2, name: "partner-marketplace" },
];

const NAMES = MARKETPLACES.map((marketplace) => marketplace.name ?? "");
const VETTERS = ["secret-scan", "prompt-injection", "license-scan"];
const OVERRIDES = overridesOf(chainOverrides, vetterToggles, MARKETPLACES);

const meta = {
  title: "Vetting/Governance",
  decorators: [
    (Story) => (
      <MemoryRouter>
        <Story />
      </MemoryRouter>
    ),
  ],
} satisfies Meta;

export default meta;

/** Nothing in the estate departs from the default — the state a converged estate is in. */
export const DefaultOnly: StoryObj = {
  render: () => <OverridesTable overrides={[]} onClear={() => {}} />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByText(/No marketplace overrides the default chain/),
    ).toBeVisible();
  },
};

/** Two marketplaces that do depart, and in what — one of them in the portal's dark theme. */
export const OverridesPresent: StoryObj = {
  render: () => <OverridesTable overrides={OVERRIDES} onClear={() => {}} />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("mode: stop-after-fail")).toBeVisible();
    await expect(canvas.getByText("secret-scan: off")).toBeVisible();
    // The control names the marketplace it would act on: "Clear override" alone would be three
    // identically named buttons on a longer estate.
    await expect(
      canvas.getByRole("button", { name: "Clear every chain override on corp-marketplace" }),
    ).toBeEnabled();
  },
};

export const OverridesPresentDark: StoryObj = {
  ...OverridesPresent,
  parameters: { theme: "dark" },
};

/**
 * A stored override whose marketplace is no longer registered. It still gets a row — a setting
 * nobody can see is worse than one labelled by its id — and its clear control is not offered,
 * because there is no name to address it by.
 */
export const OverrideOfAVanishedMarketplace: StoryObj = {
  render: () => (
    <OverridesTable overrides={overridesOf(chainOverrides, vetterToggles, [])} onClear={() => {}} />
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("marketplace #1 — no longer registered")).toBeVisible();
    await expect(
      canvas.getByRole("button", { name: "Clear every chain override on marketplace #1" }),
    ).toBeDisabled();
  },
};

/** Nothing can be applied before the plan has been read: the confirm step is reached by keyboard. */
export const BulkConfirm: StoryObj = {
  render: () => (
    <BulkEditor
      overrides={OVERRIDES}
      marketplaces={NAMES}
      vetters={VETTERS}
      onApply={() => {}}
    />
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const review = canvas.getByRole("button", { name: "Review the change" });
    // Disabled until something is selected, and the hint says why.
    await expect(review).toBeDisabled();
    await expect(canvas.getByText("Select at least one marketplace to continue.")).toBeVisible();

    await userEvent.click(canvas.getByRole("checkbox", { name: "All marketplaces" }));
    await expect(review).toBeEnabled();
    await userEvent.click(review);

    // The confirm step states the act and the before/after of every marketplace it touches.
    await expect(
      await canvas.findByText("Stop the chain at the first failure for 2 marketplaces"),
    ).toBeVisible();
    await expect(canvas.getByRole("columnheader", { name: "Now" })).toBeVisible();
    await expect(canvas.getByRole("columnheader", { name: "After" })).toBeVisible();
    await expect(canvas.getByText("no mode override")).toBeVisible();
    await expect(
      canvas.getByRole("button", { name: "Apply to these marketplaces" }),
    ).toBeEnabled();
  },
};

export const BulkConfirmDark: StoryObj = {
  ...BulkConfirm,
  parameters: { theme: "dark" },
};

/**
 * One marketplace was refused. The result leads with that, and is an alert rather than a status:
 * a partial failure reported as a success is the failure this page exists to avoid.
 */
export const BulkPartialFailure: StoryObj = {
  render: () => (
    <BulkEditor
      overrides={OVERRIDES}
      marketplaces={NAMES}
      vetters={VETTERS}
      onApply={() => {}}
      result={bulkPartialFailure}
    />
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const alert = canvas.getByRole("alert");
    await expect(alert).toHaveTextContent("1 refused, 1 applied");
    await expect(within(alert).getByText("failed")).toBeVisible();
    await expect(
      within(alert).getByText("marketplace 'partner-marketplace' not found"),
    ).toBeVisible();
    // The thread an auditor follows is on screen, not only in the ledger.
    await expect(alert).toHaveTextContent(bulkPartialFailure.correlationId ?? "");
  },
};

export const BulkPartialFailureDark: StoryObj = {
  ...BulkPartialFailure,
  parameters: { theme: "dark" },
};

/** What a session without the administrative role sees, and what it is told to do about it. */
export const NonAdminRefusal: StoryObj = {
  render: () => <VettingRefusal />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("alert")).toHaveTextContent(
      "This page needs the administrative role.",
    );
    // No control and no value: the refusal must not leak what it refuses.
    await expect(canvas.queryByRole("button")).toBeNull();
  },
};

export const NonAdminRefusalDark: StoryObj = {
  ...NonAdminRefusal,
  parameters: { theme: "dark" },
};

import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import type { MachineCredentialView } from "@/api/queries";
import { machineViews } from "@/test/msw-handlers";
import { SystemTokenTable } from "./system-tokens";

const NOW = Date.parse("2026-08-15T10:00:00Z");

const expiringSoon: MachineCredentialView = {
  id: 24,
  principal: "nightly-sync",
  name: "nightly",
  createdAt: "2026-05-01T10:00:00Z",
  expiresAt: "2026-08-25T00:00:00Z",
  apiScopes: ["marketplaces:ingest"],
  machineOwner: "ada",
  lastUsedAt: "2026-08-15T09:00:00Z",
};
const revoked: MachineCredentialView = {
  id: 25,
  principal: "retired",
  name: "retired-job",
  createdAt: "2026-01-01T10:00:00Z",
  expiresAt: "2099-01-01T00:00:00Z",
  revokedAt: "2026-07-01T00:00:00Z",
  apiScopes: ["estate:read"],
  machineOwner: "ada",
};

const meta = {
  title: "Tokens/SystemTokenTable",
  component: SystemTokenTable,
} satisfies Meta<typeof SystemTokenTable>;

export default meta;

/**
 * Every shape the list has to say something about: in use, expiring soon, never used, never
 * expiring and revoked. The marks are words, so the story reads without colour.
 */
export const Marks: StoryObj<typeof SystemTokenTable> = {
  render: () => (
    <SystemTokenTable
      tokens={[...machineViews, expiringSoon, revoked]}
      now={NOW}
      onRotate={() => {}}
      onRevoke={() => {}}
    />
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      within(canvas.getByRole("row", { name: /nightly/ })).getByText("expires within 14 days"),
    ).toBeInTheDocument();
    await expect(
      within(canvas.getByRole("row", { name: /old-job/ })).getByText("never used"),
    ).toBeInTheDocument();
    await expect(
      within(canvas.getByRole("row", { name: /forever/ })).getByText("never expires"),
    ).toBeInTheDocument();
    // A revoked credential offers nothing to act on and carries no marks.
    const dead = canvas.getByRole("row", { name: /retired-job/ });
    await expect(within(dead).queryByRole("button")).not.toBeInTheDocument();
    await expect(within(dead).getByText("revoked")).toBeInTheDocument();
  },
};

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
      onEdit={() => {}}
      onRotate={() => {}}
      onRevoke={() => {}}
    />
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // A live credential can have its scopes edited (GW_AUTH_0089); a revoked one cannot.
    await expect(canvas.getByRole("button", { name: "Edit scopes of token platform-pipeline" })).toBeInTheDocument();
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
    await expect(canvas.queryByRole("button", { name: "Edit scopes of token retired-job" })).toBeNull();
    await expect(within(dead).getByText("revoked")).toBeInTheDocument();
  },
};

/**
 * The row that clipped Revoke: a long owner address beside a principal and three actions, in a
 * container narrower than the table wants. The table scrolls sideways inside its container, so an
 * action past the edge is simply not there. Visual only: the story test project renders without
 * the app's CSS, so no assertion here could see it.
 */
export const NarrowContainer: StoryObj<typeof SystemTokenTable> = {
  render: () => (
    <div style={{ width: 900 }}>
      <SystemTokenTable
        tokens={[
          {
            id: 31,
            principal: "release-pipeline",
            name: "release-pipeline",
            createdAt: "2026-08-15T09:00:00Z",
            expiresAt: "2027-11-17T22:10:00Z",
            apiScopes: ["marketplaces:read"],
            machineOwner: "platform.engineering.team@example.com",
            lastUsedAt: "2026-08-15T09:55:00Z",
          },
        ]}
        now={NOW}
        onEdit={() => {}}
        onRotate={() => {}}
        onRevoke={() => {}}
      />
    </div>
  ),
};

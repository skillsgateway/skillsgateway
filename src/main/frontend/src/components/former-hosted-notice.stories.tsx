import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { FormerHostedList } from "./former-hosted-notice";

/**
 * The administrator's notice about repositories the removed hosted-marketplace feature left behind.
 * Temporary, like the notice: REMOVE-AFTER two minor releases after the release that ships
 * migration V3__remove_hosted_marketplaces.
 *
 * @Requirements GW_AUTH_0091
 */
const meta = {
  title: "Overview/FormerHostedList",
  component: FormerHostedList,
  decorators: [
    (Story) => (
      <div className="max-w-3xl p-4">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof FormerHostedList>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Each repository with its name, location and last commit, and nothing that deletes anything. */
export const Remaining: Story = {
  args: {
    repositories: [
      {
        name: "corp-skills",
        location: "/var/lib/skills-gateway/hosted/corp-skills.git",
        lastCommitAt: "2026-10-01T09:30:00Z",
        headSha: "3f9c2ab",
      },
      { name: "tools", location: "gateway/hosted/tools/" },
    ],
  },
  play: async ({ canvasElement }) => {
    const notice = within(canvasElement).getByRole("status", { name: "Former hosted repositories" });
    await expect(notice).toHaveTextContent("2 repositories from the former hosting feature are still");
    await expect(within(notice).getByText("corp-skills")).toBeInTheDocument();
    await expect(within(notice).queryByRole("button")).not.toBeInTheDocument();
  },
};

/** One repository reads in the singular. */
export const One: Story = {
  args: { repositories: [{ name: "corp-skills", location: "/data/hosted/corp-skills.git" }] },
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByRole("status")).toHaveTextContent(
      "1 repository from the former hosting feature is still",
    );
  },
};

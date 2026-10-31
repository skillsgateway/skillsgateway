import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { RoleRequired } from "./role-required";

const meta = {
  title: "Access/RoleRequired",
  component: RoleRequired,
} satisfies Meta<typeof RoleRequired>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A whole page behind a role: its heading, then what is missing and why. */
export const Page: Story = {
  args: {
    role: "auditor",
    title: "Audit log",
    description:
      "Append-only ledger of every facade fetch and administrative action, exportable to an external compliance system.",
    children:
      "The ledger names who fetched and who changed what, so it is shown to auditors and administrators. Ask an administrator for the role.",
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("heading", { name: "Audit log" })).toBeVisible();
    await expect(canvas.getByRole("alert")).toHaveTextContent("This page needs the auditor role.");
    // A refusal offers nothing to press and no download to follow.
    await expect(canvas.queryByRole("button")).toBeNull();
    await expect(canvas.queryByRole("link")).toBeNull();
  },
};

export const PageDark: Story = { ...Page, parameters: { theme: "dark" } };

/** A section inside a page that already has its heading, scoped to one marketplace. */
export const Section: Story = {
  args: {
    role: { approverOf: "corp-marketplace" },
    children:
      "The file-by-file comparison reads the held content, which is shown to the marketplace's approvers and to administrators.",
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.queryByRole("heading")).toBeNull();
    await expect(canvas.getByRole("alert")).toHaveTextContent(
      "This section needs the approver role for corp-marketplace.",
    );
  },
};

export const SectionDark: Story = { ...Section, parameters: { theme: "dark" } };

/** Without an explanation of its own, the statement still says what to do. */
export const Default: Story = {
  args: { role: "admin", title: "Vetting chain" },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("alert")).toHaveTextContent(
      "This page needs the administrative role.",
    );
    await expect(canvas.getByText("Ask an administrator for the role.")).toBeVisible();
  },
};

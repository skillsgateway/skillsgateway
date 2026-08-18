import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { MarkdownView } from "./markdown-view";

const meta = {
  title: "Preview/MarkdownView",
  component: MarkdownView,
} satisfies Meta<typeof MarkdownView>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A typical SKILL.md: headings, prose, inline styles, list, fenced code. */
export const Skill: Story = {
  args: {
    text: "# Hello skill\n\nSays hello with `code`, **bold** and *italic*.\n\n- one\n- two\n\n> a note\n\n```console\n$ echo hi\n```\n",
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("heading", { level: 1, name: "Hello skill" })).toBeInTheDocument();
    await expect(canvas.getByRole("list")).toBeInTheDocument();
  },
};

/** The inertness contract on display: hostile embedded HTML stays visible text. */
export const HostileHtmlStaysText: Story = {
  args: {
    text: "# Injected\n\n<img src=x onerror=alert(1)>\n",
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText(/<img src=x onerror=alert\(1\)>/)).toBeInTheDocument();
    await expect(canvas.queryByRole("img")).not.toBeInTheDocument();
  },
};

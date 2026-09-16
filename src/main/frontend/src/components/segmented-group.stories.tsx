import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, userEvent, within } from "storybook/test";
import { SegmentedGroup } from "./segmented-group";

const OPTIONS = [
  { value: "P7D", label: "7 days" },
  { value: "P30D", label: "30 days" },
  { value: "P90D", label: "90 days" },
  { value: "none", label: "No expiry" },
];

function Demo({ label, hideLabel }: { label: string; hideLabel?: boolean }) {
  const [value, setValue] = useState("P30D");
  return (
    <SegmentedGroup
      label={label}
      hideLabel={hideLabel}
      value={value}
      options={OPTIONS}
      onChange={setValue}
    />
  );
}

const meta = {
  title: "Controls/SegmentedGroup",
  component: Demo,
} satisfies Meta<typeof Demo>;

export default meta;
type Story = StoryObj<typeof meta>;

/** With a visible label, as the client wizard's "Expires" control uses it. */
export const Labelled: Story = {
  args: { label: "Expires" },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const group = await canvas.findByRole("group", { name: "Expires" });
    await expect(within(group).getByRole("button", { name: "30 days" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    await userEvent.click(within(group).getByRole("button", { name: "No expiry" }));
    await expect(within(group).getByRole("button", { name: "No expiry" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    await expect(within(group).getByRole("button", { name: "30 days" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  },
};

/** Name kept for assistive technology only, as the adoption page's report window uses it. */
export const LabelForScreenReadersOnly: Story = {
  args: { label: "Report window", hideLabel: true },
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByRole("group", { name: "Report window" }),
    ).toBeVisible();
  },
};

/** Both themes have to read the same — this is the one a native `<select>` could not deliver. */
export const Dark: Story = {
  args: { label: "Expires" },
  parameters: { theme: "dark" },
};

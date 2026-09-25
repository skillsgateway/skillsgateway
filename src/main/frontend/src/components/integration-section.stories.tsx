import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, screen, userEvent, within } from "storybook/test";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { IntegrationSection } from "./integration-section";

function Demo({ canAdd }: { canAdd: boolean }) {
  const [adding, setAdding] = useState(false);
  return (
    <IntegrationSection
      title="Audit sinks"
      description="The audit ledger pushed to an external compliance system."
      addLabel="New sink"
      addTitle="New audit sink"
      addDescription="Its signing secret is shown once, after it is added."
      canAdd={canAdd}
      adding={adding}
      onAddingChange={setAdding}
      form={
        <div className="space-y-2">
          <Label htmlFor="demo-name">Sink name</Label>
          <Input id="demo-name" />
        </div>
      }
    >
      <p className="text-sm text-muted-foreground">No export sinks yet.</p>
    </IntegrationSection>
  );
}

const meta = {
  title: "Integrations/IntegrationSection",
  component: Demo,
} satisfies Meta<typeof Demo>;

export default meta;
type Story = StoryObj<typeof meta>;

/** An administrator: the add control opens the form in a dialog. */
export const Administrator: Story = {
  args: { canAdd: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(await canvas.findByRole("button", { name: "New sink" }));
    const dialog = await screen.findByRole("dialog", { name: "New audit sink" });
    await expect(within(dialog).getByLabelText("Sink name")).toBeInTheDocument();
  },
};

/** Any other session reads the list and is offered nothing to change. */
export const ReadOnly: Story = {
  args: { canAdd: false },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByRole("heading", { name: "Audit sinks" })).toBeInTheDocument();
    await expect(canvas.queryByRole("button", { name: "New sink" })).not.toBeInTheDocument();
  },
};

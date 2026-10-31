import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import { snapshotContent } from "@/test/msw-handlers";
import { PluginInventory } from "./snapshot-inventory";
import { SourceView } from "./source-view";

const RLO = "\u202E";
const PDF = "\u202C";
const LRI = "\u2066";
const PDI = "\u2069";

const CHECK = [
  "const level = currentUser().level;",
  `if (level !== "user${RLO} ${LRI}// admin${PDI} ${LRI}") {`,
  "  return deny();",
  "}",
  "const greeting = `hello\u200Bworld`;",
  "// a team emoji stays one emoji: \u{1F468}\u200D\u{1F469}\u200D\u{1F467}",
].join("\n");

/**
 * Upstream text with invisible or bidirectional control characters: each shown as a token naming
 * its code point rather than applied, and a source line holding one marked in words.
 *
 * @Requirements GW_APPROVAL_0037, GW_APPROVAL_0038
 */
const meta = {
  title: "Snapshots/Invisible characters",
  decorators: [
    (Story) => (
      <div className="max-w-3xl p-4">
        <Story />
      </div>
    ),
  ],
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** A file whose second line reads one way and runs another; the fifth hides a zero-width space. */
export const SourceLines: Story = {
  render: () => <SourceView path="src/check.js" text={CHECK} findings={[]} />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const lines = canvas.getByRole("list", { name: "Lines of src/check.js" });
    expect(lines.textContent).not.toMatch(/[\u202A-\u202E\u2066-\u2069\u200B]/u);
    expect(canvas.getAllByText(/contains invisible or bidirectional control characters/i)).toHaveLength(2);
  },
};

/** A plugin whose name and description carry a right-to-left override. */
export const PluginName: Story = {
  render: () => (
    <PluginInventory
      plugin={{
        ...snapshotContent.plugins![1]!,
        name: `bidi-${RLO}evil${PDF}-name`,
        description: `Formats ${RLO}txt.exe${PDF} files for review.`,
      }}
    />
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByRole("button", { name: "1 skill" }));
    expect(canvas.getByRole("region", { name: "skills of bidi-⟨U+202E⟩evil⟨U+202C⟩-name" })).toBeVisible();
    expect(canvasElement.textContent).not.toMatch(/[\u202A-\u202E]/u);
  },
};

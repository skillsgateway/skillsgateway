import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import type { VettingView } from "@/api/queries";
import { twoRuleVetting, vendoredVetting, waivedVetting } from "@/test/msw-handlers";
import { VettingReport } from "./vetting-report";

/** A report over a fixed vetting view: the cache is seeded and never refetched. */
function withVetting(view: VettingView) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(["snapshot-vetting", 1], view);
  return client;
}

/**
 * The reviewer's per-vetter evidence with its waive actions: one per group, naming the rule, and
 * a selection that waives several groups at once.
 *
 * @Requirements GW_VETTING_0044, GW_VETTING_0061, GW_VETTING_0070, GW_VETTING_0071
 */
const meta = {
  title: "Vetting/VettingReport",
  component: VettingReport,
  args: { snapshotId: 1 },
  decorators: [
    (Story, context) => (
      <QueryClientProvider client={withVetting(context.parameters.vetting as VettingView)}>
        <div className="max-w-4xl p-4">
          <Story />
        </div>
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof VettingReport>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Each blocking group's action names the rule it waives and, for copies, how many. */
export const WaiveActions: Story = {
  parameters: { vetting: vendoredVetting },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByRole("button", { name: "Waive concealment-instruction at 4 locations" }),
    ).toHaveTextContent("Waive concealment-instruction at 4 locations…");
  },
};

/** The single form counts what its scope covers before anything is recorded. */
export const WaiveFormCoverage: Story = {
  parameters: { vetting: vendoredVetting },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole("button", { name: "Waive concealment-instruction at 4 locations" }),
    );
    await expect(canvas.getByText(/covers 4 findings in this snapshot/)).toBeVisible();
    await expect(
      canvas.getByRole("button", { name: "Record waiver for 4 findings of concealment-instruction" }),
    ).toBeDisabled();
  },
};

/** Groups of two rules selected together, with the one form that waives them all. */
export const MultiSelect: Story = {
  parameters: { vetting: twoRuleVetting },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole("checkbox", { name: "Select aws-access-key-id at plugins/hello/DEPLOY.md:5" }),
    );
    await userEvent.click(canvas.getByRole("checkbox", { name: "Select concealment-instruction at 4 locations" }));
    const selection = canvas.getByRole("region", { name: "Selected findings" });
    await expect(selection).toHaveTextContent("2 groups, 5 findings selected");
    await userEvent.click(within(selection).getByRole("button", { name: "Waive 2 selected groups" }));
    await expect(within(selection).getByRole("button", { name: "Record 2 waivers for 5 findings" })).toBeDisabled();
  },
};

/** The same, in the dark theme. */
export const MultiSelectDark: Story = {
  ...MultiSelect,
  parameters: { vetting: twoRuleVetting, theme: "dark" },
};

/**
 * A vetter whose only blocking finding is waived reads "waived", says what it recorded, and is
 * drawn in the outcome's colour rather than red (GW_VETTING_0071).
 */
export const WaivedVetter: Story = {
  parameters: { vetting: waivedVetting },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByText("recorded fail")).toBeVisible();
    await expect(canvas.getByRole("button", { name: /secret-scan waived/ })).toBeVisible();
    await expect(canvas.queryByText("fail")).not.toBeInTheDocument();
  },
};

/** The same, in the dark theme. */
export const WaivedVetterDark: Story = {
  ...WaivedVetter,
  parameters: { vetting: waivedVetting, theme: "dark" },
};

const longMessage =
  "a code block of these instructions installs a package at run time with npx; runs a package" +
  " runner that downloads and executes the latest published version; installs an npm package" +
  " globally (lines 77, 81, 86, 90, 94 +2 more)";

/** Five findings in three groups, one with a message long enough to wrap. */
const groupedVetting: VettingView = {
  ...vendoredVetting,
  run: {
    ...vendoredVetting.run,
    verdicts: [
      vendoredVetting.run!.verdicts![0]!,
      {
        ...vendoredVetting.run!.verdicts![1]!,
        groups: [
          ...vendoredVetting.run!.verdicts![1]!.groups!,
          {
            ruleId: "runtime-dependency",
            severity: "medium",
            message: longMessage,
            locations: ["plugins/sandbox/skills/sandbox-migrate-to-next/SKILL.md:77"],
          },
        ],
      },
    ],
  },
};

/**
 * Counts in one wording, and a long message that wraps aligned under its rule like every other
 * row, not back at the severity column (GW_VETTING_0070).
 */
export const GroupedCountsAndLongMessage: Story = {
  parameters: { vetting: groupedVetting },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByText("6 findings in 3 groups; worst high")).toBeVisible();
    const message = canvas.getByText(longMessage);
    const rule = canvas.getByText("runtime-dependency");
    // The wrapped message starts where its rule starts.
    await expect(Math.round(message.getBoundingClientRect().left)).toBe(Math.round(rule.getBoundingClientRect().left));
  },
};

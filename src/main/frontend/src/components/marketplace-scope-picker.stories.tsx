import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, userEvent, within } from "storybook/test";
import { manyMarketplaces } from "@/test/msw-handlers";
import { MarketplaceScopePicker, type ScopeSelection } from "./marketplace-scope-picker";

/** A client already holding the reads the picker makes, so the story needs no server. */
function seeded(count: number, catalog: string | null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(["marketplaces"], manyMarketplaces(count));
  if (catalog) client.setQueryData(["catalog"], { name: catalog, sha: "a".repeat(40) });
  return client;
}

function Harness({
  count,
  catalog,
  initial,
  starting,
}: {
  count: number;
  catalog: string | null;
  initial: ScopeSelection;
  starting?: string;
}) {
  const [client] = useState(() => seeded(count, catalog));
  const [value, setValue] = useState(initial);
  return (
    <QueryClientProvider client={client}>
      <div className="max-w-xl">
        <MarketplaceScopePicker value={value} onChange={setValue} starting={starting} />
      </div>
    </QueryClientProvider>
  );
}

const meta = {
  title: "Tokens/MarketplaceScopePicker",
  component: MarketplaceScopePicker,
} satisfies Meta<typeof MarketplaceScopePicker>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The default: every marketplace, and what choosing less costs stated beside it. */
export const EveryMarketplace: Story = {
  args: { value: { choice: "every", chosen: [] }, onChange: () => {} },
  render: () => <Harness count={4} catalog="catalog" initial={{ choice: "every", chosen: [] }} />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByRole("button", { name: "Every marketplace" })).toHaveAttribute("aria-pressed", "true");
    await expect(canvas.queryByRole("checkbox")).toBeNull();
  },
};

/** The limited choice with nothing ticked: the hint says why a token cannot be made yet. */
export const ChosenNothingYet: Story = {
  args: { value: { choice: "chosen", chosen: [] }, onChange: () => {} },
  render: () => <Harness count={4} catalog="catalog" initial={{ choice: "chosen", chosen: [] }} />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText(/Choose at least one marketplace/)).toBeVisible();
    await expect(canvas.getByTestId("chosen-summary")).toHaveTextContent("None chosen.");
  },
};

/** Many marketplaces and the catalog: a bounded, filterable list and a summary that survives the filter. */
export const ChosenManyFiltered: Story = {
  args: { value: { choice: "chosen", chosen: [] }, onChange: () => {} },
  render: () => (
    <Harness
      count={120}
      catalog="catalog"
      initial={{ choice: "chosen", chosen: ["market-007", "market-042"] }}
    />
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.type(canvas.getByLabelText("Filter marketplaces"), "market-04");
    await expect(canvas.getByRole("checkbox", { name: "market-042" })).toBeChecked();
    await expect(canvas.getByTestId("chosen-summary")).toHaveTextContent("2 chosen: market-007, market-042.");
  },
};

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { PluginInventory } from "@/components/snapshot-inventory";
import { VettingFlow } from "@/components/vetting-flow";
import { PresenceTable } from "./adoption";
import { AuditPage } from "./audit";
import { MarketplacesPage } from "./marketplaces";
import { WebhooksPage } from "./webhooks";

// jsdom does no layout, so these assert the class and markup contracts that keep long text inside
// its box (#776), as overflow-guards.test.tsx does for dialogs.

function withProviders(children: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // Seeded rather than fetched, so an administrator's controls are there from the first render.
  queryClient.setQueryData(["me"], {
    username: "alice",
    roles: [{ role: "admin", source: "config" }],
    claimsTruncated: false,
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>,
  );
}

test("a_long_unbroken_plugin_description_wraps_inside_its_card", () => {
  const description = "x".repeat(5000);
  render(<PluginInventory plugin={{ name: "hello", source: "./plugins/hello", description }} />);
  expect(screen.getByText(description).className).toContain("wrap-anywhere");
});

test("a_skill_path_breaks_at_its_slashes_not_mid_name", () => {
  const { container } = render(
    <PresenceTable
      report={{
        statement: "s",
        skills: [
          {
            marketplace: "acme",
            plugin: "hello",
            skill: "hello",
            path: "plugins/hello/skills/hello/SKILL.md",
            identitiesHolding: 1,
            snapshotsDelivering: 1,
          },
        ],
        unresolved: [],
      }}
    />,
  );
  const path = container.querySelector("wbr")!.parentElement!;
  expect(path.querySelectorAll("wbr")).toHaveLength(4);
  expect(path.textContent).toBe("plugins/hello/skills/hello/SKILL.md");
  expect(path.className).not.toContain("break-all");
});

test("a_vetting_step_name_is_shown_whole", () => {
  render(
    <VettingFlow
      label="Chain"
      headline={{ result: "Clear", tone: "pass", detail: "two vetters" }}
      nodes={[
        { id: "a", kind: "vetter", label: "secret-scan", state: "pass", tone: "pass", eyebrow: "Step 1" },
        { id: "b", kind: "vetter", label: "prompt-injection", state: "pass", tone: "pass", eyebrow: "Step 2" },
      ]}
    />,
  );
  for (const name of ["secret-scan", "prompt-injection"]) {
    const label = screen.getByText(name);
    expect(label.className).not.toContain("truncate");
    expect(label.closest("li")!.className).toContain("shrink-0");
  }
});

/** A long principal is cut in the audit table and kept whole in its title. */
test("a_long_audit_principal_is_truncated_with_the_whole_value_on_hover", async () => {
  withProviders(<AuditPage />);
  const table = await screen.findByRole("table");
  const cell = within(table).getAllByRole("row")[1]!.querySelectorAll("td")[3]!;
  const principal = cell.querySelector("span")!;
  expect(principal.className).toContain("truncate");
  expect(principal).toHaveAttribute("title", principal.textContent);
});

/** Sortable headers say which way they sort; the others say nothing. */
test("sortable_column_headers_carry_aria_sort", async () => {
  const audit = withProviders(<AuditPage />);
  await screen.findByRole("table");
  expect(screen.getByRole("columnheader", { name: "When" })).toHaveAttribute("aria-sort", "descending");
  expect(screen.getByRole("columnheader", { name: "Event" })).toHaveAttribute("aria-sort", "none");
  expect(screen.getByRole("columnheader", { name: "Commit" })).not.toHaveAttribute("aria-sort");
  audit.unmount();

  const user = userEvent.setup();
  withProviders(<MarketplacesPage />);
  await screen.findByText("corp-marketplace");
  const name = screen.getByRole("columnheader", { name: "Name" });
  expect(name).toHaveAttribute("aria-sort", "ascending");
  expect(screen.getByRole("columnheader", { name: "Source" })).not.toHaveAttribute("aria-sort");
  await user.click(within(name).getByRole("button", { name: "Name" }));
  expect(name).toHaveAttribute("aria-sort", "descending");
});

/** Each event checkbox carries its own accessible name rather than relying on the wrapping label. */
test("webhook_event_checkboxes_are_named", async () => {
  const user = userEvent.setup();
  withProviders(<WebhooksPage />);
  await user.click(await screen.findByRole("button", { name: "New subscriber" }));
  expect(screen.getByRole("checkbox", { name: "All events" })).toHaveAttribute("aria-label", "All events");
  const approved = screen.getByRole("checkbox", { name: "marketplace.snapshot.approved" });
  expect(approved).toHaveAttribute("aria-label", "marketplace.snapshot.approved");
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { expect, test } from "vitest";
import { MarketplaceDetailPage } from "./marketplace-detail";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/marketplaces/corp-marketplace"]}>
        <Routes>
          <Route path="/marketplaces/:name" element={<MarketplaceDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

test("deleted_snapshot_shows_its_restore_deadline_and_control", async () => {
  renderPage();
  expect(await screen.findByText("deleted")).toBeInTheDocument();
  // The deadline reads as a formatted date, but the exact instant stays machine-readable
  // on the <time> element — the audit trail must survive the presentation change.
  const deadline = screen.getByTitle(/2026-08-28T11:00:00Z/);
  expect(deadline).toHaveAttribute("datetime", "2026-08-28T11:00:00Z");
  expect(deadline.textContent).not.toBe("2026-08-28T11:00:00Z");
  expect(deadline.closest("span")).toHaveTextContent(/restorable until/);
  expect(screen.getByRole("button", { name: "Restore snapshot 2" })).toBeInTheDocument();
  // A live snapshot offers the delete control instead.
  expect(screen.getByRole("button", { name: "Delete snapshot 1" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Delete snapshot 2" })).not.toBeInTheDocument();
});

/**
 * A revoked snapshot has to explain itself. The badge alone is an outage nobody can attribute:
 * the reason, the revoking identity, and above all the list of teams that already cloned the
 * content are what turn a retraction into something an operator can act on.
 */
test("revoked_snapshot_shows_the_violation_and_who_already_fetched_it", async () => {
  renderPage();

  expect(await screen.findByText("revoked")).toBeInTheDocument();
  expect(screen.getByText(/re-vetting violation: \[secret-scan\]/)).toBeInTheDocument();
  expect(screen.getByText(/revoked by revet-policy/)).toBeInTheDocument();

  const affected = await screen.findByRole("region", {
    name: "Identities that fetched snapshot 3",
  });
  expect(affected).toBeInTheDocument();
  expect(await screen.findByText("team-payments")).toBeInTheDocument();
  expect(screen.getByText("12 fetches")).toBeInTheDocument();
  expect(screen.getByText("ci-runner")).toBeInTheDocument();

  // A revoked snapshot is not re-vetted from here: re-vetting is about content that is being
  // served, and this one is not. Deciding it again is the marketplaces page's job.
  expect(screen.queryByRole("button", { name: "Re-vet snapshot 3" })).not.toBeInTheDocument();
  // And nothing offers a fetch history for a snapshot that was never revoked.
  expect(
    screen.queryByRole("region", { name: "Identities that fetched snapshot 1" }),
  ).not.toBeInTheDocument();
});

/**
 * The reviewer's actual question is not "what is in this snapshot" but "what would approving it
 * add to what we already approved". The contents panel answers both, and the changes half shows
 * only what changed — an unchanged skill listed among the changes is the noise the panel exists
 * to remove.
 */
test("content_panel_shows_the_inventory_and_only_what_changed_since_approval", async () => {
  const user = userEvent.setup();
  renderPage();

  await user.click(await screen.findByRole("button", { name: "Show contents of snapshot 1" }));

  const inventory = await screen.findByRole("region", { name: "Contents of snapshot 1" });
  expect(within(inventory).getByText("greeting skills")).toBeInTheDocument();
  // "hello" is both the plugin and one of its skills, so both nodes carry the name.
  expect(within(inventory).getAllByText("hello").length).toBeGreaterThan(1);

  const changes = await screen.findByRole("region", {
    name: "Changes in snapshot 1 since the last approved snapshot",
  });
  expect(within(changes).getByText(/Compared with approved snapshot 2/)).toBeInTheDocument();
  expect(within(changes).getByText("111122223333")).toBeInTheDocument();
  expect(within(changes).getByText("1 added")).toBeInTheDocument();
  expect(within(changes).getByText("1 moved")).toBeInTheDocument();
  expect(within(changes).getByText("1 removed")).toBeInTheDocument();

  // The relocated skill names where it came from instead of reading as a deletion plus an add.
  expect(within(changes).getByText("critique")).toBeInTheDocument();
  expect(within(changes).getByText("from hello")).toBeInTheDocument();
  // The plugin the snapshot no longer declares is still shown, with its skills removed.
  expect(within(changes).getByText("legacy")).toBeInTheDocument();
  expect(within(changes).getByText("oldtool")).toBeInTheDocument();
  // The unchanged skill is in the inventory above and nowhere in the changes: the hello plugin
  // changed, but only one of its two skills did.
  const helloChanges = within(changes).getByRole("list", { name: "Changed skills in hello" });
  expect(within(helloChanges).getAllByRole("listitem")).toHaveLength(1);
  expect(within(helloChanges).getByText("greet")).toBeInTheDocument();
});

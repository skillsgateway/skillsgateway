import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { expect, test } from "vitest";
import type { components } from "@/api/types.gen";
import { clearVetting, heldSnapshot, marketplace } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { MarketplaceDetailPage } from "./marketplace-detail";

type Schemas = components["schemas"];

function Address() {
  const location = useLocation();
  return <output data-testid="address">{location.search}</output>;
}

function renderPage(search = "") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/marketplaces/corp-marketplace${search}`]}>
        <Routes>
          <Route
            path="/marketplaces/:name"
            element={
              <>
                <MarketplaceDetailPage />
                <Address />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function withMarketplace(view: Schemas["MarketplaceView"]) {
  server.use(http.get("/api/v1/marketplaces", () => HttpResponse.json([view])));
}

function held(id: number, day: number): Schemas["Snapshot"] {
  return {
    id,
    marketplaceId: 1,
    sha: `${String(id).padStart(4, "0")}`.repeat(10),
    state: "held",
    createdAt: `2026-08-${String(day).padStart(2, "0")}T10:00:00Z`,
  };
}

test("deleted_snapshot_shows_its_restore_deadline_and_control", async () => {
  const user = userEvent.setup();
  renderPage();
  // A deleted snapshot is history: it sits under the collapsed count until asked for.
  await user.click(await screen.findByRole("button", { name: /Earlier snapshots \(1\)/ }));
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
  const user = userEvent.setup();
  renderPage();

  await user.click(await screen.findByRole("button", { name: "Open snapshot 3" }));
  const card = await screen.findByRole("region", { name: "Snapshot 3" });
  expect(within(card).getByText("revoked")).toBeInTheDocument();
  expect(within(card).getByText(/re-vetting violation: \[secret-scan\]/)).toBeInTheDocument();
  expect(within(card).getByText(/revoked by revet-policy/)).toBeInTheDocument();

  const affected = await within(card).findByRole("region", {
    name: "Identities that fetched snapshot 3",
  });
  expect(affected).toBeInTheDocument();
  expect(await within(card).findByText("team-payments")).toBeInTheDocument();
  expect(within(card).getByText("12 fetches")).toBeInTheDocument();
  expect(within(card).getByText("ci-runner")).toBeInTheDocument();

  // A revoked snapshot is not re-vetted from here: re-vetting is about content that is being
  // served, and this one is not. Deciding it again is what the card's foot is for.
  expect(screen.queryByRole("button", { name: "Re-vet snapshot 3" })).not.toBeInTheDocument();
  expect(within(card).getByRole("button", { name: "Approve snapshot 3" })).toHaveTextContent(
    "Re-approve",
  );
  // And nothing offers a fetch history for a snapshot that was never revoked.
  expect(
    screen.queryByRole("region", { name: "Identities that fetched snapshot 1" }),
  ).not.toBeInTheDocument();
});

/**
 * The reviewer's actual question is not "what is in this snapshot" but "what would approving it
 * add to what we already approved". The inventory answers the first, the Diff tab the second —
 * and shows only what changed: an unchanged skill listed among the changes is the noise the
 * panel exists to remove.
 */
test("content_panel_shows_the_inventory_and_only_what_changed_since_approval", async () => {
  const user = userEvent.setup();
  renderPage();

  const card = await screen.findByRole("region", { name: "Snapshot 1" });
  await user.click(within(card).getByRole("tab", { name: "Inventory" }));
  const inventory = await screen.findByRole("region", { name: "Contents of snapshot 1" });
  expect(within(inventory).getByText("greeting skills")).toBeInTheDocument();
  // "hello" is both the plugin and one of its skills, so both nodes carry the name.
  expect(within(inventory).getAllByText("hello").length).toBeGreaterThan(1);

  await user.click(within(card).getByRole("tab", { name: "Diff" }));
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
  // The unchanged skill is in the inventory and nowhere in the changes: the hello plugin
  // changed, but only one of its two skills did.
  const helloChanges = within(changes).getByRole("list", { name: "Changed skills in hello" });
  expect(within(helloChanges).getAllByRole("listitem")).toHaveLength(1);
  expect(within(helloChanges).getByText("greet")).toBeInTheDocument();
});

/**
 * The decision control comes after everything it rests on — in the document, not merely on the
 * same card. Asserted as document order, because "both are present" is also true of the layout
 * this rule exists to forbid.
 *
 * @SVCs SVC_GW_APPROVAL_0018
 */
test("approve_is_never_rendered_above_the_evidence_it_rests_on", async () => {
  renderPage();
  const card = await screen.findByRole("region", { name: "Snapshot 1" });
  const approve = within(card).getByRole("button", { name: "Approve snapshot 1" });
  const evidence = [
    within(card).getByTestId("snapshot-identity"),
    await within(card).findByTestId("snapshot-delta"),
    within(card).getByRole("tablist"),
    await within(card).findByRole("region", { name: /Vetting of snapshot 1/ }),
  ];
  for (const element of evidence) {
    expect(element.compareDocumentPosition(approve) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  }
  // And the delta leads with what is arriving, then how big it is, then against what.
  expect(within(card).getByTestId("snapshot-delta")).toHaveTextContent(
    /^1 skill added, 1 modified, 1 moved, 1 removed · 3 files · \+1 −1 · vs 11112222$/,
  );
});

/**
 * A blocked snapshot's control is shut on the card and says why, rather than opening a dialog
 * whose confirm then fails. The fixture's vetting is blocked.
 *
 * @SVCs SVC_GW_APPROVAL_0018
 */
test("a_blocked_snapshot_cannot_be_approved_from_the_card_and_says_why", async () => {
  renderPage();
  const card = await screen.findByRole("region", { name: "Snapshot 1" });
  const approve = within(card).getByRole("button", { name: "Approve snapshot 1" });
  const reason = await within(card).findByText(/Vetting blocked this snapshot/);
  expect(approve).toBeDisabled();
  expect(approve).toHaveAttribute("aria-describedby", reason.id);
  // Rejecting is never gated by vetting.
  expect(within(card).getByRole("button", { name: "Reject snapshot 1" })).toBeEnabled();
});

test("a_clear_snapshot_can_be_approved_from_the_card_through_the_review_dialog", async () => {
  server.use(http.get("/api/v1/snapshots/:id/vetting", () => HttpResponse.json(clearVetting)));
  const user = userEvent.setup();
  renderPage();
  const card = await screen.findByRole("region", { name: "Snapshot 1" });
  const approve = within(card).getByRole("button", { name: "Approve snapshot 1" });
  await expect.poll(() => approve.hasAttribute("disabled")).toBe(false);
  await user.click(approve);
  expect(await screen.findByRole("dialog", { name: "Approve snapshot 1" })).toBeInTheDocument();
});

/**
 * Two snapshots can await a decision at once. Both are listed, newest first; only the newest is
 * open; and the open one says what approving it does to the other — nothing, which is the point.
 *
 * @SVCs SVC_GW_APPROVAL_0018
 */
test("two_snapshots_awaiting_are_both_listed_and_the_open_one_names_the_other", async () => {
  renderPage();
  const awaiting = await screen.findByRole("region", { name: /Awaiting decision \(2\)/ });
  const items = within(awaiting).getAllByRole("listitem");
  expect(items).toHaveLength(2);
  expect(within(items[0]!).getByRole("region", { name: "Snapshot 1" })).toBeInTheDocument();
  expect(within(items[1]!).getByRole("button", { name: "Open snapshot 3" })).toBeInTheDocument();
  expect(screen.getAllByTestId("snapshot-card")).toHaveLength(1);

  const others = within(items[0]!).getByTestId("others-awaiting");
  expect(others).toHaveTextContent(/snapshot 3 \(99998888\) also awaits a decision/);
  expect(others).toHaveTextContent(/it stays held/);
  expect(others).toHaveTextContent(/would serve content older than this/);
  expect(others).not.toHaveTextContent(/supersede/);
});

/**
 * An approved record left behind by a withdrawal is not served. The page reads the served commit
 * from the marketplace read and says nothing is served — it does not point at the approved row.
 *
 * @SVCs SVC_GW_INGEST_0033
 */
test("serving_nothing_while_an_approved_row_exists_says_nothing_is_served", async () => {
  withMarketplace({
    ...marketplace,
    servedSha: undefined,
    snapshots: [{ ...heldSnapshot, state: "approved", decidedBy: "alice" }],
  });
  renderPage();
  expect(await screen.findByTestId("serving-nothing")).toHaveTextContent(
    /Nothing is served\. A snapshot is still recorded approved, but it was withdrawn/,
  );
  expect(screen.getByTestId("setup-lead")).toHaveTextContent("Not being served yet");
  const serving = screen.getByRole("region", { name: "Serving" });
  expect(within(serving).queryByRole("listitem")).not.toBeInTheDocument();
});

test("the_served_snapshot_is_the_one_the_marketplace_read_names", async () => {
  withMarketplace({
    ...marketplace,
    servedSha: heldSnapshot.sha,
    snapshots: [{ ...heldSnapshot, state: "approved", decidedBy: "alice" }, held(5, 20)],
  });
  renderPage();
  const serving = await screen.findByRole("region", { name: "Serving" });
  expect(within(serving).getByText(heldSnapshot.sha!.slice(0, 12))).toBeInTheDocument();
  expect(screen.getByTestId("setup-lead")).toHaveTextContent("Use this marketplace");
});

/**
 * One address names the snapshot, the tab and the file, and opening it cold restores all three.
 *
 * @SVCs SVC_GW_INGEST_0032
 */
test("a_deep_link_restores_the_snapshot_the_tab_and_the_file", async () => {
  const user = userEvent.setup();
  renderPage(`?snapshot=3&tab=contents&path=${encodeURIComponent("plugins/hello/skills/hello/SKILL.md")}`);

  const card = await screen.findByRole("region", { name: "Snapshot 3" });
  expect(within(card).getByRole("tab", { name: "Contents" })).toHaveAttribute("aria-selected", "true");
  const tree = await within(card).findByRole("navigation", { name: "File tree of snapshot 3" });
  expect(await within(tree).findByRole("button", { name: /SKILL\.md/ })).toHaveAttribute(
    "aria-current",
    "true",
  );
  // The newest awaiting snapshot is not open: the address chose, not the default.
  expect(screen.queryByRole("region", { name: "Snapshot 1" })).not.toBeInTheDocument();

  // Moving puts the move in the address, naming the snapshot explicitly.
  await user.click(within(tree).getByRole("button", { name: ".claude-plugin" }));
  await user.click(within(tree).getByRole("button", { name: /marketplace\.json/ }));
  expect(screen.getByTestId("address")).toHaveTextContent(
    "?snapshot=3&tab=contents&path=.claude-plugin%2Fmarketplace.json",
  );
  await user.click(within(card).getByRole("tab", { name: "Provenance" }));
  expect(screen.getByTestId("address")).toHaveTextContent("?snapshot=3&tab=provenance");
});

/**
 * The regression test for the reason this layout exists: with twelve snapshots the page still
 * opens exactly one card and runs exactly one vetting report — the rest are one line each, and
 * history is a collapsed count.
 */
test("page_length_does_not_grow_with_the_number_of_snapshots", async () => {
  const snapshots = Array.from({ length: 12 }, (_, i) =>
    i < 2 ? held(10 + i, 10 + i) : { ...held(10 + i, 10 + i), state: "rejected" as const },
  );
  withMarketplace({ ...marketplace, snapshots });
  renderPage();

  await screen.findByRole("region", { name: "Snapshot 11" });
  expect(screen.getAllByTestId("snapshot-card")).toHaveLength(1);
  expect(await screen.findAllByRole("region", { name: /Vetting of snapshot/ })).toHaveLength(1);
  expect(screen.getByRole("button", { name: /Earlier snapshots \(10\)/ })).toHaveAttribute(
    "aria-expanded",
    "false",
  );
  expect(screen.queryByRole("button", { name: "Open snapshot 12" })).not.toBeInTheDocument();
});

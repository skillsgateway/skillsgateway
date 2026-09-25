import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { expect, test } from "vitest";
import type { components } from "@/api/types.gen";
import { clearVetting, compositeProvenance, heldSnapshot, marketplace, tooYoung } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import {
  MarketplaceActivityPage,
  MarketplaceLayout,
  MarketplaceReviewPage,
  MarketplaceSettingsPage,
  MarketplaceSnapshotsPage,
} from "./marketplace-detail";

type Schemas = components["schemas"];

function Address() {
  const location = useLocation();
  return <output data-testid="address">{location.search}</output>;
}

function Location() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname + location.search}</output>;
}

/** The marketplace's routes as the portal declares them; `section` is "", "snapshots", … */
function renderPage(search = "", section = "") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const at = `/marketplaces/corp-marketplace${section ? `/${section}` : ""}${search}`;
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[at]}>
        <Routes>
          <Route path="/marketplaces" element={<Location />} />
          <Route
            path="/marketplaces/:name"
            element={
              <>
                <MarketplaceLayout />
                <Address />
                <Location />
              </>
            }
          >
            <Route index element={<MarketplaceReviewPage />} />
            <Route path="snapshots" element={<MarketplaceSnapshotsPage />} />
            <Route path="activity" element={<MarketplaceActivityPage />} />
            <Route path="settings" element={<MarketplaceSettingsPage />} />
          </Route>
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
  // A deleted snapshot is history: it is listed under Snapshots, not on Review.
  const snapshots = renderPage("", "snapshots");
  await screen.findByRole("region", { name: /Earlier snapshots \(1\)/ });
  expect(await screen.findByText("deleted")).toBeInTheDocument();
  // The deadline reads as a formatted date, but the exact instant stays machine-readable
  // on the <time> element — the audit trail must survive the presentation change.
  const deadline = screen.getByTitle(/2026-08-28T11:00:00Z/);
  expect(deadline).toHaveAttribute("datetime", "2026-08-28T11:00:00Z");
  expect(deadline.textContent).not.toBe("2026-08-28T11:00:00Z");
  expect(deadline.closest("span")).toHaveTextContent(/restorable until/);
  expect(screen.getByRole("button", { name: "Restore snapshot 2" })).toBeInTheDocument();
  snapshots.unmount();
  // A live snapshot, on Review, offers the delete control instead.
  renderPage();
  expect(await screen.findByRole("button", { name: "Delete snapshot 1" })).toBeInTheDocument();
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
  renderPage("", "snapshots");
  expect(await screen.findByTestId("serving-nothing")).toHaveTextContent(
    /Nothing is served\. A snapshot is still recorded approved, but it was withdrawn/,
  );
  expect(screen.getByTestId("marketplace-served-status")).toHaveTextContent(/Not served.*404/);
  const serving = screen.getByRole("region", { name: "Serving" });
  expect(within(serving).queryByRole("listitem")).not.toBeInTheDocument();
});

test("the_served_snapshot_is_the_one_the_marketplace_read_names", async () => {
  withMarketplace({
    ...marketplace,
    servedSha: heldSnapshot.sha,
    snapshots: [{ ...heldSnapshot, state: "approved", decidedBy: "alice" }, held(5, 20)],
  });
  renderPage("", "snapshots");
  const serving = await screen.findByRole("region", { name: "Serving" });
  expect(within(serving).getByText(heldSnapshot.sha!.slice(0, 12))).toBeInTheDocument();
  expect(screen.getByTestId("marketplace-served-status")).toHaveTextContent(
    `Serving ${heldSnapshot.sha!.slice(0, 12)}`,
  );
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
  await user.click(within(tree).getByRole("button", { name: /^\.claude-plugin,/ }));
  await user.click(await within(tree).findByRole("button", { name: /marketplace\.json/ }));
  expect(screen.getByTestId("address")).toHaveTextContent(
    "?snapshot=3&tab=contents&path=.claude-plugin%2Fmarketplace.json",
  );
  await user.click(within(card).getByRole("tab", { name: "Provenance" }));
  expect(screen.getByTestId("address")).toHaveTextContent("?snapshot=3&tab=provenance");
});

/**
 * The regression test for the reason this layout exists: with twelve snapshots each section
 * still opens at most one card and runs at most one vetting report — the rest are one line each.
 * Review holds only what awaits a decision; history is on Snapshots.
 */
test("page_length_does_not_grow_with_the_number_of_snapshots", async () => {
  const snapshots = Array.from({ length: 12 }, (_, i) =>
    i < 2 ? held(10 + i, 10 + i) : { ...held(10 + i, 10 + i), state: "rejected" as const },
  );
  withMarketplace({ ...marketplace, snapshots });
  const review = renderPage();

  await screen.findByRole("region", { name: "Snapshot 11" });
  expect(screen.getAllByTestId("snapshot-card")).toHaveLength(1);
  expect(await screen.findAllByRole("region", { name: /Vetting of snapshot/ })).toHaveLength(1);
  expect(screen.queryByRole("button", { name: "Open snapshot 12" })).not.toBeInTheDocument();
  review.unmount();

  renderPage("", "snapshots");
  const earlier = await screen.findByRole("region", { name: /Earlier snapshots \(10\)/ });
  expect(within(earlier).getAllByRole("button", { name: /Open snapshot/ })).toHaveLength(10);
  expect(screen.queryAllByTestId("snapshot-card")).toHaveLength(0);
});

/**
 * An older link to a snapshot that no longer awaits a decision still lands on it: Review opens
 * the addressed snapshot in place and says where it now belongs, rather than dropping it.
 */
test("a_link_to_a_snapshot_not_awaiting_opens_it_on_review_and_points_to_snapshots", async () => {
  renderPage("?snapshot=2");
  const linked = await screen.findByRole("region", { name: "Linked snapshot" });
  expect(within(linked).getByRole("region", { name: "Snapshot 2" })).toBeInTheDocument();
  expect(within(linked).getByRole("link", { name: "See it among the snapshots" })).toHaveAttribute(
    "href",
    "/marketplaces/corp-marketplace/snapshots?snapshot=2",
  );
  // Only the addressed card is open: the awaiting list stays one line each.
  expect(screen.getAllByTestId("snapshot-card")).toHaveLength(1);
});

/**
 * The header is on every section, and carries the marketplace's actions — ingestion and the
 * client wizard — with the served state stated beside them.
 *
 * @SVCs SVC_GW_AUTH_0043
 */
test("the_header_offers_ingest_and_the_client_wizard_on_every_section", async () => {
  const user = userEvent.setup();
  for (const section of ["", "snapshots", "activity", "settings"]) {
    const view = renderPage("", section);
    expect(await screen.findByRole("heading", { level: 1, name: "corp-marketplace" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ingest corp-marketplace" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Connect a client" })).toBeInTheDocument();
    expect(screen.getByTestId("marketplace-served-status")).toHaveTextContent(/Not served.*404/);
    view.unmount();
  }
  renderPage("", "settings");
  await user.click(await screen.findByRole("button", { name: "Connect a client" }));
  // The wizard says the held case again, inside itself.
  expect(await screen.findByTestId("setup-held-notice")).toHaveTextContent("404");
});

test("ingest_opens_what_arrived_on_review", async () => {
  server.use(
    http.post("/api/v1/marketplaces/:name/ingest", () =>
      HttpResponse.json({ ...held(9, 30) }, { status: 201 }),
    ),
  );
  const user = userEvent.setup();
  renderPage("", "settings");
  await user.click(await screen.findByRole("button", { name: "Ingest corp-marketplace" }));
  await expect
    .poll(() => screen.getByTestId("location").textContent)
    .toBe("/marketplaces/corp-marketplace?snapshot=9");
});

test("settings_shows_the_upstream_and_the_activity_section_shows_the_ledger", async () => {
  const settings = renderPage("", "settings");
  const upstream = await screen.findByText("Upstream");
  expect(upstream).toBeInTheDocument();
  expect(screen.getByText("https://github.com/corp/marketplace.git", { selector: "dd" })).toBeInTheDocument();
  // No snapshot is rendered on Settings: the decision is not here.
  expect(screen.queryByTestId("snapshot-card")).not.toBeInTheDocument();
  settings.unmount();
  renderPage("", "activity");
  expect(await screen.findByRole("heading", { name: "Audit log" })).toBeInTheDocument();
});

async function openFile(user: ReturnType<typeof userEvent.setup>, path: string, text: string) {
  server.use(
    http.get("/api/v1/snapshots/:id/file", () =>
      HttpResponse.json({ path, size: text.length, binary: false, truncated: false, text }),
    ),
  );
  renderPage(`?snapshot=1&tab=contents&path=${encodeURIComponent(path)}`);
  const card = await screen.findByRole("region", { name: "Snapshot 1" });
  return { card, user };
}

/**
 * JSON is re-indented by its tokens: a key declared twice is shown twice, in order, and the
 * stored bytes are one control away.
 *
 * @SVCs SVC_GW_INGEST_0032
 */
test("a_json_manifest_is_shown_reindented_with_a_duplicated_key_kept", async () => {
  const user = userEvent.setup();
  const stored = '{"name":"demo","source":"./safe","source":"https://evil.example/x"}';
  const { card } = await openFile(user, ".claude-plugin/marketplace.json", stored);
  const formatted = await within(card).findByText(/"source": "\.\/safe"/);
  expect(formatted.textContent).toBe(
    '{\n  "name": "demo",\n  "source": "./safe",\n  "source": "https://evil.example/x"\n}',
  );
  const view = within(card).getByRole("group", { name: "JSON view" });
  expect(within(view).getByRole("button", { name: "Formatted" })).toHaveAttribute("aria-pressed", "true");
  await user.click(within(view).getByRole("button", { name: "Raw" }));
  expect(within(card).getByText(stored)).toBeInTheDocument();
});

/** @SVCs SVC_GW_INGEST_0032 */
test("a_file_named_json_that_does_not_tokenise_is_shown_as_stored", async () => {
  const user = userEvent.setup();
  const stored = "{'name': 'not json'}";
  const { card } = await openFile(user, "plugins/hello/plugin.json", stored);
  expect(await within(card).findByText("Not valid JSON — shown as stored.")).toBeInTheDocument();
  expect(within(card).getByText(stored)).toBeInTheDocument();
  expect(within(card).queryByRole("group", { name: "JSON view" })).not.toBeInTheDocument();
});

/**
 * The cooling-off window (GW_APPROVAL_0004.4) as a reviewer meets it on the card: the control is
 * shut and says when it opens. Untagged: SVC_GW_APPROVAL_0004.4 is verified by the Java suite.
 */
test("approve_is_disabled_with_the_remaining_time_inside_the_cooling_off_window", async () => {
  server.use(
    http.get("/api/v1/snapshots/:id/vetting", () => HttpResponse.json(clearVetting)),
    http.get("/api/v1/snapshots/:id/release-age", () => HttpResponse.json(tooYoung)),
  );
  renderPage();
  const card = await screen.findByRole("region", { name: "Snapshot 1" });
  const approve = within(card).getByRole("button", { name: "Approve snapshot 1" });
  expect(await within(card).findByText(/Inside the cooling-off window; it becomes approvable in 2d 4h/)).toBeInTheDocument();
  expect(approve).toBeDisabled();
  // Rejecting is never age-gated: suspicious content must be refusable at once.
  expect(within(card).getByRole("button", { name: "Reject snapshot 1" })).toBeEnabled();
});

test("approve_is_offered_normally_once_the_window_has_passed", async () => {
  server.use(http.get("/api/v1/snapshots/:id/vetting", () => HttpResponse.json(clearVetting)));
  renderPage();
  const card = await screen.findByRole("region", { name: "Snapshot 1" });
  const approve = within(card).getByRole("button", { name: "Approve snapshot 1" });
  await waitFor(() => expect(approve).toBeEnabled());
});

/**
 * The provenance tab carries the closure (GW_INGEST_0030.5): the served commit beside the upstream
 * one, and each external plugin with the URL it was fetched through and the commit it resolved
 * to. Untagged: SVC_GW_INGEST_0030.5 is verified by the Java suite.
 */
test("provenance_lists_the_served_commit_and_the_resolved_closure", async () => {
  const user = userEvent.setup();
  renderPage();
  const card = await screen.findByRole("region", { name: "Snapshot 1" });
  await user.click(within(card).getByRole("tab", { name: "Provenance" }));
  const member = compositeProvenance.closure!.members![0]!;
  expect(await within(card).findByText(member.cloneUrl!)).toBeInTheDocument();
  expect(within(card).getByText(member.resolvedSha!)).toBeInTheDocument();
  expect(within(card).getByText(compositeProvenance.upstreamSha!)).toBeInTheDocument();
  expect(within(card).getByRole("heading", { name: "External plugin sources" })).toBeInTheDocument();
});

/** A blocking finding is waived where it is shown, and the form demands a live justification. */
test("a_blocking_finding_is_waived_from_the_vetting_tab_with_a_justification", async () => {
  const user = userEvent.setup();
  renderPage();
  const card = await screen.findByRole("region", { name: "Snapshot 1" });
  expect(await within(card).findByText(/an AWS access key id is committed/)).toBeInTheDocument();

  await user.click(within(card).getByRole("button", { name: "Waive finding aws-access-key-id at plugins/hello/DEPLOY.md:5" }));
  const record = within(card).getByRole("button", { name: "Record waiver for aws-access-key-id" });
  expect(record).toBeDisabled();
  expect(within(card).getByLabelText("Expires on")).toHaveValue();

  await user.type(within(card).getByLabelText("Justification"), "documented dummy key");
  expect(record).toBeEnabled();
  // Approve stays shut until the waiver is actually recorded.
  expect(within(card).getByRole("button", { name: "Approve snapshot 1" })).toBeDisabled();

  // The server refuses a waiver that has already lapsed, so the control refuses it first.
  const expiry = within(card).getByLabelText("Expires on");
  await user.clear(expiry);
  await user.type(expiry, "2020-01-01");
  expect(record).toBeDisabled();
});

/**
 * A failed last ingest is stated on the marketplace's page — when, and why — because an automated
 * ingest has nobody waiting for its response; a successful one states nothing.
 *
 * @SVCs SVC_GW_INGEST_0039
 */
test("a_failed_last_ingest_is_stated_with_when_and_why", async () => {
  const reason =
    "repository not found or requires authentication (https://github.com/corp/marketplace.git: not found). Check the clone URL for typos.";
  withMarketplace({
    ...marketplace,
    lastIngestAt: "2026-09-24T08:30:00Z",
    lastIngestOutcome: "failed",
    lastIngestReason: reason,
  });
  const failed = renderPage();
  const alert = await screen.findByTestId("marketplace-last-ingest-failed");
  expect(alert).toHaveAttribute("role", "alert");
  expect(alert).toHaveTextContent(/Last ingest failed/);
  expect(alert).toHaveTextContent(reason);
  expect(within(alert).getByTitle(/2026-09-24T08:30:00Z/)).toBeInTheDocument();
  failed.unmount();

  withMarketplace({
    ...marketplace,
    lastIngestAt: "2026-09-24T09:00:00Z",
    lastIngestOutcome: "succeeded",
    lastIngestReason: undefined,
  });
  renderPage("", "settings");
  expect(await screen.findByRole("heading", { level: 1, name: "corp-marketplace" })).toBeInTheDocument();
  expect(screen.queryByTestId("marketplace-last-ingest-failed")).not.toBeInTheDocument();
  expect(screen.getByText("Last ingest", { selector: "dt" })).toBeInTheDocument();
  expect(screen.getByText(/succeeded/, { selector: "dd" })).toBeInTheDocument();
});

function asAdmin() {
  server.use(
    http.get("/api/v1/me", () =>
      HttpResponse.json<Schemas["MeView"]>({
        username: "alice",
        roles: [{ role: "admin", source: "config" }],
        claimsTruncated: false,
        version: "0.3.0",
      }),
    ),
  );
}

/**
 * Removal is an administrator's act, stated before it is taken, and it asks for the reason the
 * server requires. Other users are not shown it.
 *
 * @SVCs SVC_GW_INGEST_0048
 */
test("an_admin_removes_the_marketplace_from_settings_on_a_stated_reason", async () => {
  asAdmin();
  let sent: unknown = null;
  server.use(
    http.delete("/api/v1/marketplaces/:name", async ({ request, params }) => {
      sent = { name: params.name, body: await request.json() };
      return HttpResponse.json<Schemas["Removal"]>({ id: 1, name: "corp-marketplace", withdrawnSnapshotIds: [3, 4] });
    }),
  );
  const user = userEvent.setup();
  renderPage("", "settings");
  await user.click(await screen.findByRole("button", { name: "Remove corp-marketplace…" }));

  const dialog = await screen.findByRole("dialog", { name: "Remove corp-marketplace" });
  expect(within(dialog).getByText(/serves nothing under this name/)).toBeInTheDocument();
  expect(within(dialog).getByText(/grants to fetch it are kept/)).toBeInTheDocument();
  expect(within(dialog).getByText(/registered again, as a new marketplace/)).toBeInTheDocument();
  const confirm = within(dialog).getByRole("button", { name: "Remove corp-marketplace" });
  const reason = within(dialog).getByLabelText("Reason");
  expect(reason).toHaveAccessibleDescription(/A reason is required/);
  expect(confirm).toBeDisabled();
  await user.type(reason, "   ");
  expect(confirm).toBeDisabled();
  await user.type(reason, "upstream moved  ");
  expect(confirm).toBeEnabled();

  await user.click(confirm);
  await expect.poll(() => screen.getByTestId("location").textContent).toBe("/marketplaces");
  expect(sent).toEqual({ name: "corp-marketplace", body: { reason: "upstream moved" } });
});

/** @SVCs SVC_GW_INGEST_0048 */
test("a_refused_removal_is_stated_and_the_dialog_stays_open", async () => {
  asAdmin();
  server.use(
    http.delete("/api/v1/marketplaces/:name", () =>
      HttpResponse.json({ detail: "marketplace 'corp-marketplace' not found" }, { status: 404 }),
    ),
  );
  const user = userEvent.setup();
  renderPage("", "settings");
  await user.click(await screen.findByRole("button", { name: "Remove corp-marketplace…" }));
  const dialog = await screen.findByRole("dialog", { name: "Remove corp-marketplace" });
  await user.type(within(dialog).getByLabelText("Reason"), "mistake");
  await user.click(within(dialog).getByRole("button", { name: "Remove corp-marketplace" }));
  await waitFor(() =>
    expect(within(dialog).getByRole("button", { name: "Remove corp-marketplace" })).toBeEnabled(),
  );
  expect(screen.getByRole("dialog", { name: "Remove corp-marketplace" })).toBeInTheDocument();
  expect(screen.getByTestId("location").textContent).toBe("/marketplaces/corp-marketplace/settings");
});

/** @SVCs SVC_GW_INGEST_0048 */
test("a_user_who_is_not_an_administrator_is_not_shown_removal", async () => {
  let identified = false;
  server.use(
    http.get("/api/v1/me", () => {
      identified = true;
      return HttpResponse.json<Schemas["MeView"]>({
        username: "bob",
        roles: [{ role: "approver", marketplace: "corp-marketplace", source: "config" }],
        claimsTruncated: false,
        version: "0.3.0",
      });
    }),
  );
  renderPage("", "settings");
  expect(await screen.findByText("Upstream")).toBeInTheDocument();
  // Absence proves nothing until the roles it depends on have arrived.
  await waitFor(() => expect(identified).toBe(true));
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(screen.queryByRole("heading", { name: "Remove marketplace" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /^Remove / })).not.toBeInTheDocument();
});

/**
 * A declared marketplace would be registered again by the next reconciliation, so it is not
 * offered for removal, and the page says what to do instead.
 *
 * @SVCs SVC_GW_INGEST_0049
 */
test("a_declared_marketplace_is_not_offered_for_removal", async () => {
  asAdmin();
  server.use(
    http.get("/api/v1/estate", () =>
      HttpResponse.json<Schemas["EstateReconciliation"]>({
        trigger: "startup",
        entries: [{ kind: "marketplace", name: "corp-marketplace", action: "unchanged" }],
      }),
    ),
  );
  renderPage("", "settings");
  const button = await screen.findByRole("button", { name: "Remove corp-marketplace…" });
  await waitFor(() => expect(button).toHaveAccessibleDescription(/declared in the estate configuration/));
  expect(button).toBeDisabled();
  expect(button).toHaveAccessibleDescription(/Remove the declaration/);
});

/** Only a marketplace entry of the same name declares it; a grant naming it does not. */
test("an_estate_that_does_not_declare_the_marketplace_leaves_removal_available", async () => {
  asAdmin();
  server.use(
    http.get("/api/v1/estate", () =>
      HttpResponse.json<Schemas["EstateReconciliation"]>({
        entries: [
          { kind: "marketplace", name: "other", action: "created" },
          { kind: "grant", name: "alice/approver/corp-marketplace", action: "created" },
        ],
      }),
    ),
  );
  renderPage("", "settings");
  const button = await screen.findByRole("button", { name: "Remove corp-marketplace…" });
  await waitFor(() => expect(button).toBeEnabled());
});

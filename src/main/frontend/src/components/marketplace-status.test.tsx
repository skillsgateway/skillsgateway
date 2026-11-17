import { render, screen } from "@testing-library/react";
import { expect, test } from "vitest";
import type { MarketplaceView, Snapshot } from "@/api/queries";
import { MarketplaceStatus } from "./marketplace-status";

const snap = (id: number, state: NonNullable<Snapshot["state"]>, created: string, extra: Partial<Snapshot> = {}): Snapshot => ({
  id,
  marketplaceId: 1,
  sha: `${String(id).repeat(40)}`.slice(0, 40),
  upstreamSha: `${String(id).repeat(40)}`.slice(0, 40),
  state,
  createdAt: created,
  ...extra,
});

const approvedAt = { decidedAt: "2026-11-10T10:00:00Z", decidedBy: "ada" };

function market(snapshots: Snapshot[], extra: Partial<MarketplaceView> = {}): MarketplaceView {
  return { id: 1, name: "corp", snapshots, servedSha: null, ...extra } as MarketplaceView;
}

// No QueryClientProvider and no router around it: the panel takes the payload and nothing else,
// so it can make no read of any snapshot's contents, files or findings.
function show(marketplace: MarketplaceView) {
  return render(<MarketplaceStatus marketplace={marketplace} />);
}

const FIRST = snap(1, "approved", "2026-11-01T10:00:00Z", approvedAt);

/**
 * A session with no role is told what is served, what is waiting and why, from the payload alone.
 *
 * @SVCs SVC_GW_AUTH_0091
 */
test("up_to_date_says_what_is_served_since_when_and_by_whom", () => {
  show(market([FIRST], { servedSha: FIRST.sha }));
  const served = screen.getByTestId("status-served");
  expect(served).toHaveTextContent("Served now: 111111111111");
  expect(served).toHaveTextContent("approved by ada");
  // "Since when" is the machine-readable instant, not the relative wording: that wording depends on
  // today's date ("6 days ago", then "last week" from the seventh day on) and this fixture is fixed.
  expect(served.querySelector("time")).toHaveAttribute("datetime", FIRST.decidedAt);
  expect(screen.getByTestId("status-up-to-date")).toBeInTheDocument();
  expect(screen.queryByTestId("status-newer")).not.toBeInTheDocument();
});

/** @SVCs SVC_GW_AUTH_0091 */
test("an_automatic_approval_is_said_to_be_automatic_and_not_a_person", () => {
  const auto = snap(1, "approved", "2026-11-01T10:00:00Z", { decidedAt: "2026-11-01T10:05:00Z", decidedBy: "auto-approver" });
  show(market([auto], { servedSha: auto.sha }));
  expect(screen.getByTestId("status-served")).toHaveTextContent("approved by automatic approval");
  expect(screen.getByTestId("status-served")).not.toHaveTextContent("auto-approver");
});

/** @SVCs SVC_GW_AUTH_0091 */
test("a_newer_held_commit_is_awaiting_approval_and_names_the_role_but_no_person", () => {
  const held = snap(2, "held", "2026-11-12T10:00:00Z");
  show(market([FIRST, held], { servedSha: FIRST.sha }));
  const newer = screen.getByTestId("status-newer");
  expect(newer).toHaveTextContent("222222222222");
  expect(newer).toHaveTextContent("awaiting approval");
  expect(screen.getByTestId("status-who-decides")).toHaveTextContent(
    "An approver of this marketplace, or an administrator, decides it — ask your gateway administrators.",
  );
  expect(screen.queryByTestId("status-up-to-date")).not.toBeInTheDocument();
});

/** @SVCs SVC_GW_AUTH_0091 */
test.each([
  ["rejected", "rejected"],
  ["revoked", "revoked"],
] as const)("a_newer_%s_commit_is_said_to_be_%s_and_does_not_ask_for_an_approval", (state, word) => {
  show(market([FIRST, snap(2, state, "2026-11-12T10:00:00Z")], { servedSha: FIRST.sha }));
  expect(screen.getByTestId("status-newer")).toHaveTextContent(word);
  expect(screen.queryByTestId("status-who-decides")).not.toBeInTheDocument();
});

/** @SVCs SVC_GW_AUTH_0091 */
test("a_failed_ingest_gives_the_reason_the_header_already_shows", () => {
  show(
    market([FIRST], {
      servedSha: FIRST.sha,
      lastIngestOutcome: "failed",
      lastIngestAt: "2026-11-13T10:00:00Z",
      lastIngestReason: "upstream refused the clone: 403; check the credentials",
    }),
  );
  expect(screen.getByTestId("status-ingest-failed")).toHaveTextContent("upstream refused the clone: 403");
  expect(screen.queryByTestId("status-up-to-date")).not.toBeInTheDocument();
});

/** @SVCs SVC_GW_AUTH_0091 */
test.each([
  ["nothing ingested", [], /Nothing has been ingested/],
  ["never approved", [snap(1, "held", "2026-11-01T10:00:00Z")], /ever been approved/],
  ["withdrawn", [snap(1, "approved", "2026-11-01T10:00:00Z", approvedAt)], /withdrawn since/],
  ["revoked since", [snap(1, "revoked", "2026-11-01T10:00:00Z", approvedAt)], /withdrawn since/],
])("nothing_served_because_%s_says_so", (_name, snapshots, pattern) => {
  show(market(snapshots as Snapshot[]));
  expect(screen.getByTestId("status-served")).toHaveTextContent("Nothing is served.");
  expect(screen.getByTestId("status-served")).toHaveTextContent(pattern);
});

/** @SVCs SVC_GW_AUTH_0091 */
test("a_purged_snapshot_is_not_reported_and_an_older_decided_one_is_not_called_newer", () => {
  const purged = snap(3, "held", "2026-11-14T10:00:00Z", { deletedAt: "2026-11-15T10:00:00Z" });
  const olderRejected = snap(0, "rejected", "2026-10-01T10:00:00Z");
  show(market([olderRejected, FIRST, purged], { servedSha: FIRST.sha }));
  expect(screen.queryByTestId("status-newer")).not.toBeInTheDocument();
  expect(screen.getByTestId("status-up-to-date")).toBeInTheDocument();
});

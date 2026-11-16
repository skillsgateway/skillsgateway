import type { MarketplaceView, Snapshot } from "@/api/queries";

/** The identity automatic approval records as the decider (AutoApprovalService.ACTOR). */
export const AUTO_APPROVER = "auto-approver";

export type ServedNow = {
  sha: string;
  /** When it became the served one: the decision that approved it. */
  since: string | undefined;
  /** A person, or "automatic approval". */
  approvedBy: string | undefined;
};

/** Why nothing is served: never approved, or approved once and withdrawn since. */
export type NothingServed = "nothing-ingested" | "never-approved" | "withdrawn";

export type NewerState = "awaiting-approval" | "rejected" | "revoked";

export type Newer = {
  sha: string;
  state: NewerState;
  /** When it was ingested, so the wait can be stated. */
  since: string | undefined;
};

export type MarketplaceStatusView = {
  served: ServedNow | null;
  nothingServed: NothingServed | null;
  newer: Newer | null;
  /** The reason the header already shows, when the last ingest attempt failed. */
  ingestFailed: { at: string | undefined; reason: string | undefined } | null;
  /** True when something is served, nothing newer waits and the last ingest did not fail. */
  upToDate: boolean;
};

const newestFirst = (a: Snapshot, b: Snapshot) =>
  Date.parse(b.createdAt ?? "") - Date.parse(a.createdAt ?? "");

/**
 * Why a client may not have the newest version, from nothing but the marketplace payload every
 * signed-in session already receives: the served reference, each snapshot's state and decision,
 * and the last ingest's outcome. It adds no read, so it can show no more than a session without a
 * role could already read (GW_AUTH_0075).
 *
 * "Served" follows the rule the Snapshots page uses, the served reference and not "the newest
 * approved", which is wrong exactly after a withdrawal leaves an approved record serving nothing.
 *
 * @Requirements GW_AUTH_0075
 */
export function statusOf(marketplace: MarketplaceView): MarketplaceStatusView {
  const live = [...(marketplace.snapshots ?? [])].filter((s) => !s.deletedAt).sort(newestFirst);
  const servedSha = marketplace.servedSha ?? null;
  const servedSnapshot =
    servedSha === null ? undefined : live.find((s) => s.sha === servedSha && s.state === "approved");

  const served: ServedNow | null =
    servedSha === null
      ? null
      : {
          sha: servedSha,
          since: servedSnapshot?.decidedAt,
          approvedBy: servedSnapshot
            ? servedSnapshot.decidedBy === AUTO_APPROVER
              ? "automatic approval"
              : servedSnapshot.decidedBy
            : undefined,
        };

  // Newer than what is served: the newest snapshot, if it is not the served one and is not
  // older than it. With nothing served, the newest snapshot is simply what is waiting.
  const newest = live[0];
  let newer: Newer | null = null;
  if (newest && newest !== servedSnapshot) {
    const olderThanServed =
      servedSnapshot !== undefined &&
      Date.parse(newest.createdAt ?? "") <= Date.parse(servedSnapshot.createdAt ?? "");
    const state: NewerState | null =
      newest.state === "held"
        ? "awaiting-approval"
        : newest.state === "rejected"
          ? "rejected"
          : newest.state === "revoked"
            ? "revoked"
            : null;
    if (!olderThanServed && state) newer = { sha: newest.sha ?? "", state, since: newest.createdAt };
  }

  let nothingServed: NothingServed | null = null;
  if (served === null) {
    const wasApproved = live.some((s) => s.state === "approved" || s.state === "revoked");
    nothingServed =
      live.length === 0 ? "nothing-ingested" : wasApproved ? "withdrawn" : "never-approved";
  }

  const ingestFailed =
    marketplace.lastIngestOutcome === "failed"
      ? { at: marketplace.lastIngestAt, reason: marketplace.lastIngestReason }
      : null;

  return {
    served,
    nothingServed,
    newer,
    ingestFailed,
    upToDate: served !== null && newer === null && ingestFailed === null,
  };
}

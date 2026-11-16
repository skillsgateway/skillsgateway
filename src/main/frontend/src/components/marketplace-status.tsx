import { CircleCheck, Hourglass } from "lucide-react";
import type { MarketplaceView } from "@/api/queries";
import { Timestamp } from "@/components/timestamp";
import { statusOf, type NothingServed, type NewerState } from "@/lib/marketplace-status";

const NEWER: Record<NewerState, string> = {
  "awaiting-approval": "awaiting approval",
  rejected: "rejected",
  revoked: "revoked",
};

const NOTHING: Record<NothingServed, string> = {
  "nothing-ingested": "Nothing has been ingested from this marketplace yet, so there is nothing to serve.",
  "never-approved":
    "No commit of this marketplace has ever been approved, so a client gets nothing until one is.",
  withdrawn:
    "A commit was approved once but has been withdrawn since, so a client gets nothing until one is approved again.",
};

/**
 * Why a client may not have the newest version: what is served now, whether something newer
 * waits and in what state, who decides it, and why nothing is served. Written for anyone signed
 * in, not for an approver, and built from the marketplace payload alone — it takes no query and
 * makes no read, so it cannot show more than a session without a role may already read, and it
 * names a role rather than any person who holds it (GW_AUTH_0075).
 *
 * @Requirements GW_AUTH_0075
 */
export function MarketplaceStatus({ marketplace }: { marketplace: MarketplaceView }) {
  const status = statusOf(marketplace);
  const approved = status.served?.approvedBy;
  return (
    <section
      aria-labelledby="marketplace-status-heading"
      data-testid="marketplace-status"
      className="space-y-2 rounded-lg border p-4"
    >
      <h2 id="marketplace-status-heading" className="flex items-center gap-2 text-base font-semibold">
        {status.upToDate ? (
          <CircleCheck className="size-4" aria-hidden />
        ) : (
          <Hourglass className="size-4" aria-hidden />
        )}
        Is my client up to date?
      </h2>
      <ul className="space-y-1 text-sm">
        <li data-testid="status-served">
          {status.served ? (
            <>
              <span className="text-muted-foreground">Served now: </span>
              <span className="font-mono">{status.served.sha.slice(0, 12)}</span>
              {status.served.since ? (
                <>
                  {" "}
                  <span className="text-muted-foreground">since</span> <Timestamp value={status.served.since} relative />
                </>
              ) : null}
              {approved ? <span className="text-muted-foreground">, approved by {approved}</span> : null}.
            </>
          ) : (
            <>
              <span className="font-medium">Nothing is served. </span>
              <span className="text-muted-foreground">{NOTHING[status.nothingServed ?? "never-approved"]}</span>
            </>
          )}
        </li>
        {status.newer ? (
          <li data-testid="status-newer">
            <span className="text-muted-foreground">A newer commit, </span>
            <span className="font-mono">{status.newer.sha.slice(0, 12)}</span>
            <span className="text-muted-foreground">, is </span>
            <span className="font-medium">{NEWER[status.newer.state]}</span>
            {status.newer.since ? (
              <>
                <span className="text-muted-foreground">, ingested </span>
                <Timestamp value={status.newer.since} relative />
              </>
            ) : null}
            .
            {status.newer.state === "awaiting-approval" ? (
              <span className="text-muted-foreground" data-testid="status-who-decides">
                {" "}
                An approver of this marketplace, or an administrator, decides it — ask your gateway
                administrators.
              </span>
            ) : null}
          </li>
        ) : null}
        {status.ingestFailed ? (
          <li data-testid="status-ingest-failed" className="break-words">
            <span className="font-medium">The last ingest failed </span>
            <Timestamp value={status.ingestFailed.at} relative />
            <span className="text-muted-foreground">, so the newest upstream commit has not been picked up: </span>
            {status.ingestFailed.reason}
          </li>
        ) : null}
        {status.upToDate ? (
          <li data-testid="status-up-to-date" className="text-muted-foreground">
            Nothing newer is waiting: the served commit is the newest this gateway has.
          </li>
        ) : null}
      </ul>
    </section>
  );
}

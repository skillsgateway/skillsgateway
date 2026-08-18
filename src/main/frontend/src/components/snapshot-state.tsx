import { Timestamp } from "@/components/timestamp";
import { Badge } from "@/components/ui/badge";
import type { Snapshot } from "@/api/queries";

/**
 * The snapshot state machine as a badge: `held → approved | rejected`, plus `revoked` — the
 * retroactive quarantine a re-vetting violation causes under enforcement.
 *
 * <p>One component rather than a copy per page: a state the portal renders as a bare outline badge
 * because someone forgot to add a case is a state a reviewer cannot see the meaning of. `revoked`
 * is destructive like `rejected` because the consequence is the same — nothing is being served —
 * and the word is what distinguishes "never published" from "published and taken back".
 *
 * @Requirements GW_0055
 */
export function SnapshotStateBadge({ state }: { state?: string }) {
  switch (state) {
    case "approved":
      return <Badge>approved</Badge>;
    case "held":
      return <Badge variant="secondary">held</Badge>;
    case "rejected":
      return <Badge variant="destructive">rejected</Badge>;
    case "revoked":
      return <Badge variant="destructive">revoked</Badge>;
    default:
      return <Badge variant="outline">{state ?? "unknown"}</Badge>;
  }
}

/**
 * Who revoked a snapshot and when — shown next to the badge so a retraction is never a state with
 * nobody attached to it. What it was revoked *for* is the snapshot's `violation`, rendered once by
 * the card itself; repeating it here would make the same sentence appear twice on one page.
 * Renders nothing for a snapshot that was not revoked.
 *
 * @Requirements GW_0055
 */
export function RevocationNote({ snapshot }: { snapshot: Snapshot }) {
  if (snapshot.state !== "revoked") {
    return null;
  }
  return (
    <span className="text-xs text-muted-foreground">
      revoked by {snapshot.revokedBy ?? "the gateway"}
      {snapshot.revokedAt ? <> on <Timestamp value={snapshot.revokedAt} /></> : null}
    </span>
  );
}

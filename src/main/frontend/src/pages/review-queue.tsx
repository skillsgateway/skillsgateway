import { Link } from "react-router-dom";
import { useAwaitingDecision } from "@/api/queries";
import { SnapshotStateBadge } from "@/components/snapshot-state";
import { Timestamp } from "@/components/timestamp";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { SnapshotVettingBadge } from "@/components/vetting-report";

/**
 * The review queue: what awaits a decision, across every marketplace. Each entry opens the
 * snapshot on its marketplace's review, where the report, diff and contents sit beside the
 * decision. The queue deliberately offers no approve or reject of its own.
 *
 * @Requirements GW_INGEST_0037
 */
export function ReviewQueuePage() {
  const queue = useAwaitingDecision();
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Review queue</h1>
        <p className="text-sm text-muted-foreground">
          Snapshots awaiting a decision, across every marketplace, newest first.
        </p>
      </div>
      {queue.isLoading ? <p className="text-sm text-muted-foreground">Loading…</p> : null}
      {queue.isError ? (
        <p role="alert" className="text-sm text-destructive">
          {queue.error.message}
        </p>
      ) : null}
      {queue.isSuccess && queue.rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          Nothing awaits a decision.{" "}
          <Link to="/marketplaces" className="text-primary hover:underline">
            See the marketplaces
          </Link>
          .
        </p>
      ) : null}
      {queue.rows.length > 0 ? (
        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Marketplace</TableHead>
                <TableHead>Commit</TableHead>
                <TableHead>State</TableHead>
                <TableHead>Vetting</TableHead>
                <TableHead>Ingested</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {queue.rows.map(({ marketplace, snapshot }) => (
                <TableRow key={snapshot.id}>
                  <TableCell>
                    <Link to={`/marketplaces/${marketplace}`} className="font-medium hover:underline">
                      {marketplace}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Link
                      to={`/marketplaces/${marketplace}?snapshot=${snapshot.id}`}
                      aria-label={`Review snapshot ${snapshot.sha?.slice(0, 12)} of ${marketplace}`}
                      className="font-mono text-xs text-primary hover:underline"
                    >
                      {snapshot.sha?.slice(0, 12)}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <SnapshotStateBadge state={snapshot.state} />
                  </TableCell>
                  <TableCell>
                    <SnapshotVettingBadge snapshotId={snapshot.id ?? 0} />
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-xs text-muted-foreground">
                    <Timestamp value={snapshot.createdAt} />
                    {snapshot.ingestedBy ? ` by ${snapshot.ingestedBy}` : ""}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      ) : null}
    </div>
  );
}

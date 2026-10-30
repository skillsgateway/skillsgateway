import { ArrowDown, ArrowUp, ChevronsUpDown } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router-dom";
import { useAwaitingDecision, useSnapshotVetting } from "@/api/queries";
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
import { ariaSort } from "@/lib/aria-sort";

type SortKey = "ingested" | "marketplace";
type Sort = { key: SortKey; dir: "asc" | "desc" };

/**
 * How many findings the snapshot's latest vetting run recorded, waived or not. Read from the same
 * report the vetting badge beside it reads, so it costs no request of its own.
 */
function FindingCount({ snapshotId }: { snapshotId: number }) {
  const vetting = useSnapshotVetting(snapshotId);
  if (vetting.isLoading) return <span className="text-xs text-muted-foreground">…</span>;
  if (vetting.isError) return <span className="text-xs text-muted-foreground">unavailable</span>;
  const run = vetting.data?.run;
  if (!run) return <span className="text-xs text-muted-foreground">not run</span>;
  const count = (run.verdicts ?? []).reduce((sum, verdict) => sum + (verdict.findings ?? []).length, 0);
  return <span className="tabular-nums">{count}</span>;
}

/** A header that orders the queue; the arrow states the current direction, if it is the ordering column. */
function SortButton({
  label,
  sorted,
  onToggle,
}: {
  label: string;
  sorted: false | "asc" | "desc";
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      className="-ml-1 inline-flex items-center gap-1 rounded px-1 py-0.5 hover:text-foreground"
    >
      {label}
      {sorted === "asc" ? (
        <ArrowUp className="size-3" aria-hidden />
      ) : sorted === "desc" ? (
        <ArrowDown className="size-3" aria-hidden />
      ) : (
        <ChevronsUpDown className="size-3 text-muted-foreground/50" aria-hidden />
      )}
    </button>
  );
}

/**
 * The review queue: what awaits a decision, across every marketplace. Each entry opens the
 * snapshot on its marketplace's review, where the report, diff and contents sit beside the
 * decision. The queue deliberately offers no approve or reject of its own.
 *
 * @Requirements GW_INGEST_0037
 */
export function ReviewQueuePage() {
  const queue = useAwaitingDecision();
  // Newest first, as the queue has always opened; the reader can turn it to oldest first, which is
  // what has waited longest, or group it by marketplace.
  const [sort, setSort] = useState<Sort>({ key: "ingested", dir: "desc" });
  const sortedBy = (key: SortKey) => (sort.key === key ? sort.dir : false);
  const toggle = (key: SortKey) =>
    setSort((current) =>
      current.key === key
        ? { key, dir: current.dir === "asc" ? "desc" : "asc" }
        : { key, dir: key === "ingested" ? "desc" : "asc" },
    );
  const sign = sort.dir === "asc" ? 1 : -1;
  const ingested = (row: (typeof queue.rows)[number]) => Date.parse(row.snapshot.createdAt ?? "") || 0;
  // Within one marketplace the newest stays first, whichever way the marketplaces run.
  const rows = [...queue.rows].sort((a, b) =>
    sort.key === "marketplace"
      ? sign * a.marketplace.localeCompare(b.marketplace) || ingested(b) - ingested(a)
      : sign * (ingested(a) - ingested(b)),
  );
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Review queue</h1>
        <p className="text-sm text-muted-foreground">
          Snapshots awaiting a decision, across every marketplace, with how long each has waited and
          what its vetting found. Newest first until you order it otherwise.
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
                <TableHead aria-sort={ariaSort(sortedBy("marketplace"))}>
                  <SortButton
                    label="Marketplace"
                    sorted={sortedBy("marketplace")}
                    onToggle={() => toggle("marketplace")}
                  />
                </TableHead>
                <TableHead>Commit</TableHead>
                <TableHead>State</TableHead>
                <TableHead>Vetting</TableHead>
                <TableHead>Findings</TableHead>
                <TableHead aria-sort={ariaSort(sortedBy("ingested"))}>
                  <SortButton
                    label="Ingested"
                    sorted={sortedBy("ingested")}
                    onToggle={() => toggle("ingested")}
                  />
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map(({ marketplace, snapshot }) => (
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
                  <TableCell>
                    <FindingCount snapshotId={snapshot.id ?? 0} />
                  </TableCell>
                  {/* Relative, because the question is how long it has waited; the instant is on hover. */}
                  <TableCell className="text-xs text-muted-foreground">
                    <span className="flex gap-1 whitespace-nowrap">
                      <Timestamp value={snapshot.createdAt} relative />
                      {snapshot.ingestedBy ? (
                        // A principal is as long as its identity provider makes it; cut, whole on hover.
                        <span className="max-w-48 truncate" title={snapshot.ingestedBy}>
                          by {snapshot.ingestedBy}
                        </span>
                      ) : null}
                    </span>
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

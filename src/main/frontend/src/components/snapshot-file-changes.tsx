import { ChevronDown, ChevronRight, FileDiff } from "lucide-react";
import { useState, type ReactNode } from "react";
import { useSnapshotDiffPages, type SnapshotDiffEntry } from "@/api/queries";
import { DiffText } from "@/components/snapshot-explorer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

/** Entries per page of `GET /snapshots/{id}/diff`, for the "Show more" label. */
const DIFF_PAGE = 500;

function TypeBadge({ type }: { type?: string }) {
  if (type === "removed") return <Badge variant="destructive">removed</Badge>;
  if (type === "added") return <Badge>added</Badge>;
  return <Badge variant="secondary">{type ?? "modified"}</Badge>;
}

function EntryBody({ entry }: { entry: SnapshotDiffEntry }) {
  if (entry.binary) return <p className="text-xs text-muted-foreground">Binary file — no text diff.</p>;
  if (!entry.diff) return <p className="text-xs text-muted-foreground">No text diff for this entry.</p>;
  return (
    <div className="space-y-2">
      {entry.truncated ? (
        <p className="text-xs text-muted-foreground">Truncated: the diff was cut at its limit.</p>
      ) : null}
      <DiffText diff={entry.diff} />
    </div>
  );
}

/** The counts worth stating; a zero is left out rather than shown as a zero. */
function chips(summary: { added?: number; modified?: number; removed?: number; binary?: number }) {
  return [
    { label: "added", count: summary.added ?? 0 },
    { label: "modified", count: summary.modified ?? 0 },
    { label: "removed", count: summary.removed ?? 0 },
    { label: "binary", count: summary.binary ?? 0 },
  ].filter((chip) => chip.count > 0);
}

/**
 * Every file this snapshot changes against what the marketplace currently serves, a page at a
 * time, each with its diff one click away. The counts are the gateway's over the whole diff, so
 * the heading states the size of the review even before the last page is read.
 *
 * Distinct from the skill-level changes above it, which are against the last approved snapshot.
 *
 * @Requirements GW_APPROVAL_0028, GW_APPROVAL_0026
 */
export function SnapshotFileChanges({ snapshotId }: { snapshotId: number }) {
  const diff = useSnapshotDiffPages(snapshotId);
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set());

  const toggle = (path: string) =>
    setOpen((current) => {
      const next = new Set(current);
      if (!next.delete(path)) next.add(path);
      return next;
    });

  let body: ReactNode;
  if (diff.isPending) {
    body = (
      <p role="status" className="text-sm text-muted-foreground">
        Reading the comparison against the served commit…
      </p>
    );
  } else if (diff.isError) {
    body = (
      <p role="alert" className="text-sm text-destructive">
        The comparison against the served commit could not be read: {diff.error.message}
      </p>
    );
  } else {
    const first = diff.data.pages[0];
    const entries = diff.data.pages.flatMap((page) => page.entries ?? []);
    const total = first?.total ?? entries.length;
    const summary = first?.summary ?? {};
    if (!first?.baselineSha) {
      body = (
        <p className="text-sm text-muted-foreground">
          Nothing is currently served for this marketplace, so{" "}
          {total === 1 ? "its one file is" : `all ${total} files are`} new. Read them in the
          Contents tab.
        </p>
      );
    } else if (total === 0) {
      body = <p className="text-sm text-muted-foreground">No file differs from the served commit.</p>;
    } else {
      body = (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm text-muted-foreground">
              {total} {total === 1 ? "file" : "files"} against
            </span>
            <span className="font-mono text-xs text-muted-foreground">
              {first.baselineSha.slice(0, 12)}
            </span>
            {chips(summary).map((chip) => (
              <span key={chip.label} className="rounded-md border bg-muted px-2 py-0.5 text-xs">
                {chip.count} {chip.label}
              </span>
            ))}
            <span className="font-mono text-xs text-muted-foreground">
              +{summary.linesAdded ?? 0} −{summary.linesRemoved ?? 0}
            </span>
          </div>
          <ul aria-label={`Changed files in snapshot ${snapshotId}`} className="divide-y rounded-md border">
            {entries.map((entry) => {
              const path = entry.path ?? "";
              const expanded = open.has(path);
              return (
                <li key={path}>
                  <button
                    type="button"
                    aria-expanded={expanded}
                    onClick={() => toggle(path)}
                    className="flex w-full items-center gap-2 px-3 py-1.5 text-left hover:bg-muted outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                  >
                    {expanded ? (
                      <ChevronDown className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                    ) : (
                      <ChevronRight className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                    )}
                    <span title={path} className="min-w-0 flex-1 truncate font-mono text-xs">
                      {path}
                    </span>
                    <TypeBadge type={entry.type} />
                  </button>
                  {expanded ? (
                    <div className="px-3 pb-3">
                      <EntryBody entry={entry} />
                    </div>
                  ) : null}
                </li>
              );
            })}
          </ul>
          {diff.hasNextPage ? (
            <Button
              size="sm"
              variant="outline"
              disabled={diff.isFetchingNextPage}
              onClick={() => void diff.fetchNextPage()}
            >
              {diff.isFetchingNextPage
                ? "Loading…"
                : `Show ${Math.min(DIFF_PAGE, total - entries.length)} more (${entries.length} of ${total} shown)`}
            </Button>
          ) : null}
        </div>
      );
    }
  }

  return (
    <section aria-label={`Files changed in snapshot ${snapshotId} against the served commit`}>
      <h3 className="flex items-center gap-2 text-sm font-medium">
        <FileDiff className="size-4 text-primary" aria-hidden />
        Files changed against the served commit
      </h3>
      <div className="mt-2">{body}</div>
    </section>
  );
}

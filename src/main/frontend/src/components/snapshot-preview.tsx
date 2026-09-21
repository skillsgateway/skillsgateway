import { FileSearch } from "lucide-react";
import { Link } from "react-router-dom";
import { useSnapshotFiles } from "@/api/queries";

/** The one or two paths that answer "what is this?" without opening anything. */
function quickOpenPaths(paths: readonly string[]): string[] {
  const manifest = paths.filter((path) => path === ".claude-plugin/marketplace.json");
  const firstSkill = paths.filter((path) => path.endsWith("/SKILL.md")).slice(0, 1);
  return [...manifest, ...firstSkill];
}

/**
 * The glance at a snapshot's contents, inline on the marketplace detail page: the paths that
 * identify it, each a link straight to itself in the explorer.
 *
 * Reading a commit is not a job for a 320 px box beside three other cards, so the workspace
 * this used to be lives at `/marketplaces/:name/snapshots/:id/files` — full width, its own
 * scroll, and an address that can be sent to the second approver. What stays here is the
 * glance, because the glance is the part that belonged on this page.
 *
 * @Requirements GW_INGEST_0015, GW_INGEST_0032
 */
export function SnapshotPreview({
  snapshotId,
  marketplace,
}: {
  snapshotId: number;
  marketplace: string;
}) {
  const files = useSnapshotFiles(snapshotId);
  const explorer = `/marketplaces/${encodeURIComponent(marketplace)}/snapshots/${snapshotId}/files`;

  const inspect = (
    <Link
      to={explorer}
      aria-label={`Inspect contents of snapshot ${snapshotId}`}
      className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
    >
      <FileSearch className="size-4" aria-hidden />
      Inspect contents
    </Link>
  );

  if (files.isLoading) return <p className="text-sm text-muted-foreground">Loading file tree…</p>;
  if (files.isError)
    return (
      <p role="alert" className="text-sm text-destructive">
        {files.error.message}
      </p>
    );

  const paths = (files.data?.entries ?? []).map((entry) => entry.path ?? "");
  if (paths.length === 0)
    return <p className="text-sm text-muted-foreground">This snapshot contains no files.</p>;

  return (
    <section aria-label={`Preview of snapshot ${snapshotId}`} className="space-y-2">
      <div className="flex flex-wrap items-center gap-3">
        {inspect}
        <span className="text-xs text-muted-foreground">
          {paths.length} paths{files.data?.truncated ? ", and the listing is cut at its limit" : ""}
        </span>
      </div>
      <div className="flex flex-wrap gap-2">
        {quickOpenPaths(paths).map((path) => (
          <Link
            key={path}
            to={`${explorer}?path=${encodeURIComponent(path)}`}
            className="rounded-md border bg-muted px-2 py-0.5 font-mono text-xs hover:bg-accent"
          >
            {path}
          </Link>
        ))}
      </div>
    </section>
  );
}

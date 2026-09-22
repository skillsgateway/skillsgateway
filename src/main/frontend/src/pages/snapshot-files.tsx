import { ArrowLeft } from "lucide-react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { useMarketplaces, useSnapshotFiles } from "@/api/queries";
import { SnapshotExplorer } from "@/components/snapshot-explorer";
import { SnapshotStateBadge } from "@/components/snapshot-state";
import { Badge } from "@/components/ui/badge";

function Notice({ children }: { children: React.ReactNode }) {
  return <p className="text-sm text-muted-foreground">{children}</p>;
}

/**
 * The file explorer at its own full-width address. The snapshot card's Contents tab renders the
 * same {@link SnapshotExplorer}; this route stays so links already sent keep opening.
 *
 * @Requirements GW_INGEST_0032, GW_INGEST_0015, GW_INGEST_0016
 */
export function SnapshotFilesPage() {
  const { name, id } = useParams<{ name: string; id: string }>();
  const [params, setParams] = useSearchParams();
  const snapshotId = Number(id);
  const selectedPath = params.get("path");

  const marketplaces = useMarketplaces();
  // A malformed id addresses nothing, so nothing is asked of the gateway.
  const readable = Number.isInteger(snapshotId) && snapshotId > 0 ? snapshotId : null;
  const files = useSnapshotFiles(readable);

  const select = (path: string) => {
    const next = new URLSearchParams(params);
    next.set("path", path);
    // Pushed, not replaced: back and forward walk the files the reviewer visited.
    setParams(next);
  };

  const marketplace = marketplaces.data?.find((entry) => entry.name === name);
  const snapshot = marketplace?.snapshots?.find((entry) => entry.id === snapshotId);

  const backLink = (
    <Link
      to={`/marketplaces/${encodeURIComponent(name ?? "")}`}
      className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
    >
      <ArrowLeft className="size-4" aria-hidden />
      Back to {name}
    </Link>
  );

  if (readable === null) {
    return (
      <div className="space-y-4">
        {backLink}
        <Notice>{id} is not a snapshot address.</Notice>
      </div>
    );
  }

  // The name in the address is context, not authority — the gateway gates these reads by
  // snapshot id regardless of what it says. A mismatch is a wrong link, and says so.
  if (marketplaces.isSuccess && !snapshot && files.isSuccess) {
    return (
      <div className="space-y-4">
        {backLink}
        <Notice>Snapshot {id} is not a snapshot of {name}.</Notice>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4 lg:h-full lg:min-h-0">
      <div className="space-y-2">
        {backLink}
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold">Snapshot contents</h1>
          {snapshot?.state ? <SnapshotStateBadge state={snapshot.state} /> : null}
          {snapshot?.sha ? (
            <Badge variant="outline" className="font-mono">
              {snapshot.sha.slice(0, 12)}
            </Badge>
          ) : null}
        </div>
        <p className="text-sm text-muted-foreground">
          Exactly the commit snapshot {id} pins, of {name}. This address names the file you are
          reading — send it to the second approver.
        </p>
      </div>
      <SnapshotExplorer snapshotId={readable} selectedPath={selectedPath} onSelect={select} />
    </div>
  );
}

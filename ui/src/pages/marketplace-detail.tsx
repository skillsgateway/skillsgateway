import { ArrowLeft, Puzzle } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { toast } from "sonner";
import {
  useMarketplaces,
  useRestoreSnapshot,
  useSnapshotContent,
  useSoftDeleteSnapshot,
  type Snapshot,
} from "@/api/queries";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";

function stateBadge(state?: string) {
  switch (state) {
    case "approved":
      return <Badge>approved</Badge>;
    case "held":
      return <Badge variant="secondary">held</Badge>;
    case "rejected":
      return <Badge variant="destructive">rejected</Badge>;
    default:
      return <Badge variant="outline">{state ?? "unknown"}</Badge>;
  }
}

/**
 * Retention state of one snapshot: whether it is deleted, until when it can be restored, and
 * the control that does it. An approved snapshot is served by the facade and the gateway
 * refuses to delete it (GW_0033), so no delete control is offered for one.
 *
 * @Requirements GW_0036
 */
function RetentionControls({ snapshot }: { snapshot: Snapshot }) {
  const softDelete = useSoftDeleteSnapshot();
  const restore = useRestoreSnapshot();
  const id = snapshot.id ?? 0;
  const busy = softDelete.isPending || restore.isPending;

  if (snapshot.deletedAt) {
    return (
      <>
        <Badge variant="destructive">deleted</Badge>
        <span className="text-xs text-muted-foreground">
          restorable until {snapshot.purgeAfter ?? "—"}
        </span>
        <Button
          size="sm"
          variant="outline"
          disabled={busy}
          aria-label={`Restore snapshot ${id}`}
          onClick={() =>
            restore.mutate(id, {
              onSuccess: () => toast.success(`Snapshot ${id} restored`),
              onError: (error) => toast.error(error.message),
            })
          }
        >
          Restore
        </Button>
      </>
    );
  }
  if (snapshot.state === "approved") return null;
  return (
    <Button
      size="sm"
      variant="outline"
      disabled={busy}
      aria-label={`Delete snapshot ${id}`}
      onClick={() =>
        softDelete.mutate(id, {
          onSuccess: () => toast.success(`Snapshot ${id} deleted; it can be restored`),
          onError: (error) => toast.error(error.message),
        })
      }
    >
      Delete
    </Button>
  );
}

function SnapshotContentView({ snapshotId }: { snapshotId: number }) {
  const content = useSnapshotContent(snapshotId);
  if (content.isLoading) return <p className="text-sm text-muted-foreground">Loading contents…</p>;
  if (content.isError)
    return (
      <p role="alert" className="text-sm text-destructive">
        {content.error.message}
      </p>
    );
  const plugins = content.data?.plugins ?? [];
  if (plugins.length === 0)
    return <p className="text-sm text-muted-foreground">No plugins declared in this snapshot.</p>;
  return (
    <div className="space-y-3">
      {plugins.map((plugin) => (
        <div key={plugin.name} className="rounded-md border p-3">
          <div className="flex items-center gap-2 font-medium">
            <Puzzle className="size-4 text-primary" aria-hidden />
            {plugin.name}
            <span className="font-mono text-xs text-muted-foreground">{plugin.source}</span>
          </div>
          {plugin.description ? (
            <p className="mt-1 text-sm text-muted-foreground">{plugin.description}</p>
          ) : null}
          <div className="mt-2 flex flex-wrap gap-2">
            {(plugin.skills ?? []).length === 0 ? (
              <span className="text-xs text-muted-foreground">no skills found</span>
            ) : (
              (plugin.skills ?? []).map((skill) => (
                <Badge key={skill.path} variant="outline">
                  {skill.name}
                </Badge>
              ))
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

/**
 * Marketplace detail: forge metadata, snapshots, and each snapshot's plugin/skill
 * inventory — the future surface for limiting individual plugins or skills.
 *
 * @Requirements GW_0018
 */
export function MarketplaceDetailPage() {
  const { name } = useParams<{ name: string }>();
  const marketplaces = useMarketplaces();
  const [openSnapshot, setOpenSnapshot] = useState<number | null>(null);
  const marketplace = marketplaces.data?.find((m) => m.name === name);

  if (marketplaces.isLoading) return <p>Loading…</p>;
  if (!marketplace)
    return (
      <div className="space-y-4">
        <p role="alert" className="text-sm text-destructive">
          Marketplace '{name}' not found.
        </p>
        <Button variant="outline" render={<Link to="/marketplaces">Back to marketplaces</Link>} />
      </div>
    );

  const snapshots = marketplace.snapshots ?? [];
  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <Button
          variant="ghost"
          size="icon"
          aria-label="Back to marketplaces"
          render={
            <Link to="/marketplaces">
              <ArrowLeft className="size-4" aria-hidden />
            </Link>
          }
        />
        <div>
          <h1 className="text-2xl font-semibold">{marketplace.name}</h1>
          <p className="break-all text-sm text-muted-foreground">{marketplace.url}</p>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Upstream</CardTitle>
          <CardDescription>Forge metadata captured at registration (best effort).</CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
            <dt className="font-medium">Forge</dt>
            <dd>{marketplace.forge ?? "—"}</dd>
            <dt className="font-medium">Project</dt>
            <dd>{marketplace.forgeProject ?? "—"}</dd>
            <dt className="font-medium">Description</dt>
            <dd>{marketplace.description ?? "—"}</dd>
            <dt className="font-medium">Last upstream update</dt>
            <dd>{marketplace.upstreamUpdatedAt ?? "—"}</dd>
            <dt className="font-medium">Registered</dt>
            <dd>{marketplace.createdAt}</dd>
          </dl>
        </CardContent>
      </Card>

      <div className="space-y-3">
        <h2 className="text-lg font-semibold">Snapshots</h2>
        {snapshots.length === 0 ? (
          <p className="text-sm text-muted-foreground">No snapshots yet.</p>
        ) : (
          snapshots.map((snapshot) => {
            const id = snapshot.id ?? 0;
            const open = openSnapshot === id;
            return (
              <Card key={id}>
                <CardContent className="space-y-3 py-4">
                  <div className="flex items-center gap-3">
                    <span className="font-mono text-sm">{snapshot.sha?.slice(0, 12)}</span>
                    {stateBadge(snapshot.state)}
                    {snapshot.decidedBy ? (
                      <span className="text-xs text-muted-foreground">
                        decided by {snapshot.decidedBy}
                      </span>
                    ) : null}
                    <div className="ml-auto flex items-center gap-2">
                      <RetentionControls snapshot={snapshot} />
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      aria-label={`${open ? "Hide" : "Show"} contents of snapshot ${id}`}
                      onClick={() => setOpenSnapshot(open ? null : id)}
                    >
                      {open ? "Hide contents" : "Show contents"}
                    </Button>
                  </div>
                  {snapshot.violation ? (
                    <p className="text-sm text-destructive">{snapshot.violation}</p>
                  ) : null}
                  {open ? (
                    <>
                      <Separator />
                      <SnapshotContentView snapshotId={id} />
                    </>
                  ) : null}
                </CardContent>
              </Card>
            );
          })
        )}
      </div>
    </div>
  );
}

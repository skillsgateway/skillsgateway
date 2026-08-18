import { ArrowLeft, Puzzle } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { toast } from "sonner";
import {
  useMarketplaces,
  useRestoreSnapshot,
  useRevetSnapshot,
  useSnapshotContent,
  useSnapshotFetchers,
  useSoftDeleteSnapshot,
  type Snapshot,
} from "@/api/queries";
import { Timestamp } from "@/components/timestamp";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { RevocationNote, SnapshotStateBadge } from "@/components/snapshot-state";
import { SetupWizard } from "@/components/setup-wizard";
import { SnapshotPreview } from "@/components/snapshot-preview";
import { VettingReport } from "@/components/vetting-report";

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
          restorable until <Timestamp value={snapshot.purgeAfter} dayOnly />
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

/**
 * Re-vetting of one snapshot: the control that asks for a fresh run, and — for a snapshot a
 * violation revoked — why it was taken back and who already had it.
 *
 * The affected list is the point of the panel. A revoked snapshot is not an incident the gateway
 * can close on its own: every identity named here has already cloned the content, so the operator's
 * next action is about them, not about the ref. It is fetched only for a revoked snapshot, so an
 * ordinary review never asks the ledger a question it does not need answered.
 *
 * @Requirements GW_0055
 */
function RevetPanel({ snapshot }: { snapshot: Snapshot }) {
  const id = snapshot.id ?? 0;
  const revet = useRevetSnapshot();
  const revoked = snapshot.state === "revoked";
  const fetchers = useSnapshotFetchers(revoked ? id : null);
  const approved = snapshot.state === "approved";

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <RevocationNote snapshot={snapshot} />
        {approved ? (
          <Button
            size="sm"
            variant="outline"
            className="ml-auto"
            disabled={revet.isPending}
            aria-label={`Re-vet snapshot ${id}`}
            onClick={() =>
              revet.mutate(id, {
                onSuccess: (result) =>
                  result.classification === "VIOLATION"
                    ? toast.error(
                        result.revoked
                          ? `Snapshot ${id} revoked by a re-vetting violation`
                          : `Snapshot ${id} has a re-vetting violation; it is still published`,
                      )
                    : toast.success(
                        result.classification === "INCONCLUSIVE"
                          ? `Re-vetting of snapshot ${id} could not conclude`
                          : `Snapshot ${id} re-vetted clear`,
                      ),
                onError: (error) => toast.error(error.message),
              })
            }
          >
            {revet.isPending ? "Re-vetting…" : "Re-vet now"}
          </Button>
        ) : null}
      </div>
      {revoked ? (
        <section aria-label={`Identities that fetched snapshot ${id}`} className="rounded-md border p-3">
          <h3 className="text-sm font-medium">Already fetched by</h3>
          {fetchers.isLoading ? (
            <p className="text-sm text-muted-foreground">Loading fetch history…</p>
          ) : fetchers.isError ? (
            <p role="alert" className="text-sm text-destructive">
              {fetchers.error.message}
            </p>
          ) : (fetchers.data ?? []).length === 0 ? (
            <p className="text-sm text-muted-foreground">
              Nobody fetched this snapshot's content through the facade.
            </p>
          ) : (
            <ul className="mt-2 space-y-1">
              {(fetchers.data ?? []).map((fetcher) => (
                <li key={fetcher.principal} className="flex flex-wrap items-center gap-2 text-sm">
                  <span className="font-medium">{fetcher.principal}</span>
                  <span className="rounded-md border bg-muted px-2 py-0.5 text-xs">
                    {fetcher.fetches} fetch{fetcher.fetches === 1 ? "" : "es"}
                  </span>
                  <span className="text-xs text-muted-foreground">last {fetcher.lastFetch}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}
    </div>
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
  const [openPreview, setOpenPreview] = useState<number | null>(null);
  const [wizardOpen, setWizardOpen] = useState(false);
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
        <Button className="ml-auto" onClick={() => setWizardOpen(true)}>
          Set up a client
        </Button>
      </div>
      {/* Unmounted when closed: a token minted inside lives only while the wizard is open. */}
      {wizardOpen ? (
        <SetupWizard marketplace={marketplace.name ?? ""} onClose={() => setWizardOpen(false)} />
      ) : null}

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
            <dd><Timestamp value={marketplace.upstreamUpdatedAt} /></dd>
            <dt className="font-medium">Registered</dt>
            <dd><Timestamp value={marketplace.createdAt} /></dd>
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
            const preview = openPreview === id;
            return (
              <Card key={id}>
                <CardContent className="space-y-3 py-4">
                  <div className="flex items-center gap-3">
                    <span className="font-mono text-sm">{snapshot.sha?.slice(0, 12)}</span>
                    <SnapshotStateBadge state={snapshot.state} />
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
                    <Button
                      size="sm"
                      variant="outline"
                      aria-label={`${preview ? "Hide" : "Preview"} files of snapshot ${id}`}
                      onClick={() => setOpenPreview(preview ? null : id)}
                    >
                      {preview ? "Hide preview" : "Preview files"}
                    </Button>
                  </div>
                  {snapshot.violation ? (
                    <p className="text-sm text-destructive">{snapshot.violation}</p>
                  ) : null}
                  <Separator />
                  <RevetPanel snapshot={snapshot} />
                  <VettingReport snapshotId={id} />
                  {open ? (
                    <>
                      <Separator />
                      <SnapshotContentView snapshotId={id} />
                    </>
                  ) : null}
                  {preview ? (
                    <>
                      <Separator />
                      <SnapshotPreview snapshotId={id} />
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

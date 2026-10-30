import { useState } from "react";
import { toast } from "sonner";
import {
  describeFourEyesConflicts,
  formatRemaining,
  useDecideSnapshot,
  useRestoreSnapshot,
  useRevetSnapshot,
  useSnapshotContentDiff,
  useSnapshotDiff,
  useSnapshotFetchers,
  useSnapshotFourEyes,
  useSnapshotReleaseAge,
  useSnapshotVetting,
  useSoftDeleteSnapshot,
  type Snapshot,
} from "@/api/queries";
import { ApproveDialog } from "@/components/approve-dialog";
import { ProvenanceDetails } from "@/components/provenance-details";
import { SnapshotContentDiff } from "@/components/snapshot-content-diff";
import { SnapshotDelta } from "@/components/snapshot-delta";
import { SnapshotExplorer } from "@/components/snapshot-explorer";
import { contentsHref } from "@/lib/file-findings";
import { SnapshotFileChanges } from "@/components/snapshot-file-changes";
import { SnapshotInventory } from "@/components/snapshot-inventory";
import { RevocationNote, SnapshotStateBadge } from "@/components/snapshot-state";
import { Timestamp } from "@/components/timestamp";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { VettingReport } from "@/components/vetting-report";
import { snapshotDelta } from "@/lib/snapshot-delta";
import { useMarketplaceRights } from "@/lib/marketplace-rights";
import { isDecidable, type SnapshotTab } from "@/lib/snapshot-roles";
import { RoleRequired } from "@/components/role-required";

/**
 * Retention state of one snapshot: whether it is deleted, until when it can be restored, and
 * the control that does it. An approved snapshot is served by the facade and the gateway
 * refuses to delete it (GW_RETENTION_0003), so no delete control is offered for one. Deleting and
 * restoring are administrator acts; anyone else reads the state without the control.
 *
 * @Requirements GW_RETENTION_0006, GW_AUTH_0064
 */
export function RetentionControls({
  snapshot,
  onChanged,
}: {
  snapshot: Snapshot;
  /** Told after a delete or restore lands, so the page can keep the snapshot in view as it moves. */
  onChanged?: () => void;
}) {
  const softDelete = useSoftDeleteSnapshot();
  const restore = useRestoreSnapshot();
  const { administer } = useMarketplaceRights();
  const id = snapshot.id ?? 0;
  const busy = softDelete.isPending || restore.isPending;

  if (snapshot.deletedAt) {
    return (
      <>
        <Badge variant="destructive">deleted</Badge>
        <span className="text-xs text-muted-foreground">
          restorable until <Timestamp value={snapshot.purgeAfter} dayOnly />
        </span>
        {administer ? (
          <Button
            size="sm"
            variant="outline"
            disabled={busy}
            aria-label={`Restore snapshot ${id}`}
            onClick={() =>
              restore.mutate(id, {
                onSuccess: () => {
                  toast.success(`Snapshot ${id} restored`);
                  onChanged?.();
                },
                onError: (error) => toast.error(error.message),
              })
            }
          >
            Restore
          </Button>
        ) : null}
      </>
    );
  }
  if (snapshot.state === "approved" || !administer) return null;
  return (
    <Button
      size="sm"
      variant="outline"
      disabled={busy}
      aria-label={`Delete snapshot ${id}`}
      onClick={() =>
        softDelete.mutate(id, {
          onSuccess: () => {
            toast.success(`Snapshot ${id} deleted; it can be restored`);
            onChanged?.();
          },
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
 * ordinary review never asks the ledger a question it does not need answered. Re-vetting and the
 * fetch history are both the marketplace's approvers' to see (GW_AUTH_0064).
 *
 * @Requirements GW_VETTING_0018, GW_AUTH_0064
 */
function RevetPanel({ snapshot }: { snapshot: Snapshot }) {
  const id = snapshot.id ?? 0;
  const revet = useRevetSnapshot();
  const { decide } = useMarketplaceRights();
  const revoked = snapshot.state === "revoked";
  const fetchers = useSnapshotFetchers(revoked && decide ? id : null);
  const approved = snapshot.state === "approved";
  if (!approved && !revoked) return null;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <RevocationNote snapshot={snapshot} />
        {approved && decide ? (
          <Button
            size="sm"
            variant="outline"
            className="ml-auto"
            disabled={revet.isPending}
            aria-label={`Re-vet snapshot ${id}`}
            onClick={() =>
              revet.mutate(id, {
                onSuccess: (result) =>
                  result.classification === "violation"
                    ? toast.error(
                        result.revoked
                          ? `Snapshot ${id} revoked by a re-vetting violation`
                          : `Snapshot ${id} has a re-vetting violation; it is still published`,
                      )
                    : toast.success(
                        result.classification === "inconclusive"
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
      {revoked && decide ? (
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
                  <span className="text-xs text-muted-foreground">last <Timestamp value={fetcher.lastFetch} /></span>
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}
    </div>
  );
}

/**
 * The delta line for one snapshot, from the two reads it is assembled from. The file comparison is
 * an approver's read, so a session that cannot make it is shown no line rather than a failed one.
 */
export function SnapshotDeltaLine({ snapshotId }: { snapshotId: number }) {
  const { decide } = useMarketplaceRights();
  const diff = useSnapshotDiff(decide ? snapshotId : null);
  const content = useSnapshotContentDiff(decide ? snapshotId : null);
  if (!decide) return null;
  if (diff.isPending) {
    return <p className="font-mono text-xs text-muted-foreground">Measuring the change…</p>;
  }
  if (diff.isError) {
    return (
      <p className="text-xs text-muted-foreground">
        The comparison against the served commit could not be read.
      </p>
    );
  }
  // The skill counts wait for their own read rather than the line appearing without them.
  return <SnapshotDelta delta={snapshotDelta(diff.data, content.isPending ? undefined : content.data)} />;
}

function snapshotName(snapshot: Snapshot): string {
  return `snapshot ${snapshot.id} (${snapshot.sha?.slice(0, 8) ?? "?"})`;
}

function listNames(snapshots: readonly Snapshot[]): string {
  const names = snapshots.map(snapshotName);
  if (names.length <= 1) return names.join("");
  return `${names.slice(0, -1).join(", ")} and ${names.at(-1)}`;
}

/**
 * What approving this snapshot does to the others awaiting a decision: nothing. They stay held,
 * and the one that matters is the older one — approving it afterwards would serve content older
 * than this.
 *
 * @Requirements GW_APPROVAL_0018
 */
function OthersAwaiting({ snapshot, others }: { snapshot: Snapshot; others: readonly Snapshot[] }) {
  if (others.length === 0) return null;
  const created = snapshot.createdAt ?? "";
  const older = others.filter((other) => (other.createdAt ?? "") < created);
  const newer = others.filter((other) => (other.createdAt ?? "") >= created);
  return (
    <div className="space-y-1 text-xs text-muted-foreground" data-testid="others-awaiting">
      {older.length > 0 ? (
        <p>
          {older.length === 1 ? "Older " : "Older snapshots "}
          {listNames(older)} {older.length === 1 ? "also awaits" : "also await"} a decision.
          Approving this one does not retire {older.length === 1 ? "it" : "them"}:{" "}
          {older.length === 1 ? "it stays" : "they stay"} held, and approving{" "}
          {older.length === 1 ? "it" : "one of them"} afterwards would serve content older than
          this.
        </p>
      ) : null}
      {newer.length > 0 ? (
        <p>
          Newer {listNames(newer)} {newer.length === 1 ? "also awaits" : "also await"} a decision
          and {newer.length === 1 ? "stays" : "stay"} held after this.
        </p>
      ) : null}
    </div>
  );
}

/**
 * Approve and Reject, at the foot of the card: below the identity, the delta and the tabs that
 * carry the evidence. A reason the gateway would refuse the approval is stated on the card and
 * shuts the control, rather than letting the press fail.
 *
 * @Requirements GW_APPROVAL_0018, GW_VETTING_0010, GW_APPROVAL_0004.4, GW_APPROVAL_0011
 */
function DecisionRow({ snapshot, others }: { snapshot: Snapshot; others: readonly Snapshot[] }) {
  const id = snapshot.id ?? 0;
  const decide = useDecideSnapshot();
  const [approving, setApproving] = useState(false);
  const vetting = useSnapshotVetting(id);
  const releaseAge = useSnapshotReleaseAge(id);
  const fourEyes = useSnapshotFourEyes(id);

  const outcome = vetting.data?.outcome;
  const reason = vetting.isPending
    ? "Reading the vetting verdict…"
    : vetting.isError
      ? "The vetting verdict could not be read, so this snapshot cannot be approved."
      : outcome === "blocked" || outcome === undefined
        ? "Vetting blocked this snapshot. Waive each blocking finding in the Vetting tab — with a justification and an expiry — and Approve unblocks."
        : releaseAge.data?.eligible === false
          ? `Inside the cooling-off window; it becomes approvable in ${formatRemaining(releaseAge.data.remainingSeconds ?? 0)}, with nothing to do in the meantime.`
          : fourEyes.data?.refused === true
            ? `Four-eyes rule: you ${describeFourEyesConflicts(fourEyes.data)}, so someone else with approval rights here has to approve it.`
            : null;

  return (
    <div className="space-y-3 border-t pt-4" data-testid="snapshot-decision">
      <OthersAwaiting snapshot={snapshot} others={others} />
      {reason ? (
        <p id={`approve-reason-${id}`} className="text-xs text-muted-foreground">
          {reason}
        </p>
      ) : null}
      <div className="flex flex-wrap items-center gap-2">
        <Button
          size="sm"
          disabled={reason !== null || decide.isPending}
          aria-label={`Approve snapshot ${id}`}
          aria-describedby={reason ? `approve-reason-${id}` : undefined}
          onClick={() => setApproving(true)}
        >
          {snapshot.state === "revoked" ? "Re-approve" : "Approve"}
        </Button>
        <Button
          size="sm"
          variant="destructive"
          disabled={decide.isPending}
          aria-label={`Reject snapshot ${id}`}
          onClick={() =>
            decide.mutate(
              { id, decision: "reject" },
              {
                onSuccess: () => toast.success(`Snapshot ${id} rejected`),
                onError: (error) => toast.error(error.message),
              },
            )
          }
        >
          Reject
        </Button>
      </div>
      {approving ? <ApproveDialog snapshotId={id} onClose={() => setApproving(false)} /> : null}
    </div>
  );
}

/**
 * Where the decision would be, for a session that cannot make it: which role it needs, rather than
 * a decision row that is simply missing from the snapshot the reader came to decide.
 *
 * @Requirements GW_AUTH_0064
 */
function DecisionNeedsRole({ marketplace }: { marketplace: string | null }) {
  return (
    <p className="border-t pt-4 text-xs break-words text-muted-foreground" data-testid="snapshot-decision">
      {marketplace
        ? `Approving or rejecting a snapshot of ${marketplace} needs the approver role for it.`
        : "Approving or rejecting this snapshot needs the approver role for its marketplace."}
    </p>
  );
}

/**
 * One snapshot, open: identity, then the delta, then the evidence as tabs, then the decision.
 *
 * The order is the rule, not a layout preference — a decision control is never above what it
 * rests on. The tab and the selected file are the caller's, because they belong in the address:
 * a link restores the snapshot, the tab and the file together (GW_INGEST_0032).
 *
 * @Requirements GW_APPROVAL_0018, GW_INGEST_0032
 */
export function SnapshotCard({
  snapshot,
  tab,
  path,
  onTab,
  onPath,
  line = null,
  onLine,
  others,
  onRetentionChanged,
}: {
  snapshot: Snapshot;
  tab: SnapshotTab;
  path: string | null;
  onTab: (tab: SnapshotTab) => void;
  onPath: (path: string) => void;
  /** The addressed line of the open file (GW_APPROVAL_0030). */
  line?: number | null;
  onLine?: (line: number) => void;
  /** The other snapshots awaiting a decision — named because approving this one leaves them held. */
  others: readonly Snapshot[];
  onRetentionChanged?: () => void;
}) {
  const id = snapshot.id ?? 0;
  const decidable = isDecidable(snapshot);
  const rights = useMarketplaceRights();

  return (
    <Card aria-label={`Snapshot ${id}`} role="region" data-testid="snapshot-card">
      <CardContent className="space-y-4 py-4">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2" data-testid="snapshot-identity">
          <span className="font-mono text-sm">{snapshot.sha?.slice(0, 12)}</span>
          <SnapshotStateBadge state={snapshot.state} />
          <span className="text-xs text-muted-foreground">
            ingested <Timestamp value={snapshot.createdAt} />
            {snapshot.ingestedBy ? ` by ${snapshot.ingestedBy}` : ""}
          </span>
          {snapshot.decidedBy ? (
            <span className="text-xs text-muted-foreground">decided by {snapshot.decidedBy}</span>
          ) : null}
          <div className="ml-auto flex items-center gap-2">
            <RetentionControls snapshot={snapshot} onChanged={onRetentionChanged} />
          </div>
        </div>

        <SnapshotDeltaLine snapshotId={id} />

        {snapshot.violation ? <p className="text-sm text-destructive">{snapshot.violation}</p> : null}
        <RevetPanel snapshot={snapshot} />

        <Tabs value={tab} onValueChange={(value) => onTab(value as SnapshotTab)}>
          <TabsList
            variant="line"
            aria-label={`Evidence for snapshot ${id}`}
            // Five tabs outrun a phone's width; the strip scrolls rather than the page;
            // the bottom room keeps the active underline from making it scroll vertically too.
            className="max-w-full justify-start overflow-x-auto overflow-y-hidden pb-[7px] group-data-horizontal/tabs:h-9"
          >
            <TabsTrigger value="vetting">Vetting</TabsTrigger>
            {rights.decide ? <TabsTrigger value="contents">Contents</TabsTrigger> : null}
            <TabsTrigger value="diff">Diff</TabsTrigger>
            <TabsTrigger value="inventory">Inventory</TabsTrigger>
            <TabsTrigger value="provenance">Provenance</TabsTrigger>
          </TabsList>
          <TabsContent value="vetting" className="pt-2">
            {/* Without the approver role a location stays text: Contents is not theirs to open. */}
            <VettingReport
              snapshotId={id}
              locationHref={rights.decide ? (location) => contentsHref(id, location) : undefined}
            />
          </TabsContent>
          <TabsContent value="contents" className="pt-2">
            {rights.decide ? (
              // Bounded, so the tree and the file scroll inside the card and the page does not.
              <div className="lg:h-[36rem]">
                <SnapshotExplorer
                  snapshotId={id}
                  selectedPath={path}
                  onSelect={onPath}
                  selectedLine={line}
                  onSelectLine={onLine}
                />
              </div>
            ) : (
              // Reached only by an address naming the tab; the tab itself is not offered.
              <RoleRequired role={{ approverOf: rights.marketplace ?? "this marketplace" }}>
                The contents are the held content, which is shown to the marketplace's approvers
                and to administrators.
              </RoleRequired>
            )}
          </TabsContent>
          <TabsContent value="diff" className="space-y-6 pt-2">
            <SnapshotContentDiff snapshotId={id} />
            {rights.decide ? (
              <SnapshotFileChanges snapshotId={id} />
            ) : (
              <RoleRequired role={{ approverOf: rights.marketplace ?? "this marketplace" }}>
                The file-by-file comparison reads the held content, which is shown to the
                marketplace's approvers and to administrators.
              </RoleRequired>
            )}
          </TabsContent>
          <TabsContent value="inventory" className="pt-2">
            <SnapshotInventory snapshotId={id} />
          </TabsContent>
          <TabsContent value="provenance" className="pt-2">
            <ProvenanceDetails snapshotId={id} />
          </TabsContent>
        </Tabs>

        {decidable ? (
          rights.decide ? (
            <DecisionRow snapshot={snapshot} others={others} />
          ) : (
            <DecisionNeedsRole marketplace={rights.marketplace} />
          )
        ) : null}
      </CardContent>
    </Card>
  );
}

/**
 * A snapshot that is not the open one: one line, so page length does not grow with ingest count.
 */
export function SnapshotLine({
  snapshot,
  onOpen,
  showDelta = false,
  onRetentionChanged,
}: {
  snapshot: Snapshot;
  onOpen: () => void;
  showDelta?: boolean;
  onRetentionChanged?: () => void;
}) {
  const id = snapshot.id ?? 0;
  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border px-3 py-2">
      <span className="font-mono text-sm">{snapshot.sha?.slice(0, 12)}</span>
      <SnapshotStateBadge state={snapshot.state} />
      <span className="text-xs text-muted-foreground">
        <Timestamp value={snapshot.createdAt} />
      </span>
      {showDelta ? <SnapshotDeltaLine snapshotId={id} /> : null}
      <div className="ml-auto flex items-center gap-2">
        <RetentionControls snapshot={snapshot} onChanged={onRetentionChanged} />
        <Button size="sm" variant="outline" aria-label={`Open snapshot ${id}`} onClick={onOpen}>
          Open
        </Button>
      </div>
    </li>
  );
}

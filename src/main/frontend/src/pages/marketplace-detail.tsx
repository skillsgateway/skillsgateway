import { useState } from "react";
import {
  Link,
  Outlet,
  useNavigate,
  useOutletContext,
  useParams,
  useSearchParams,
} from "react-router-dom";
import { toast } from "sonner";
import {
  useAudit,
  useIngest,
  useIsAdmin,
  useMarketplaces,
  type MarketplaceView,
  type Snapshot,
} from "@/api/queries";
import { AuditStatusBadge, auditRowClass } from "@/components/audit-status";
import { auditStatus } from "@/lib/audit-status";
import { Timestamp } from "@/components/timestamp";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { MarketplaceVettingChain } from "@/components/marketplace-vetting-chain";
import { RemoveMarketplace } from "@/components/remove-marketplace";
import { SetupWizard } from "@/components/setup-wizard";
import { SnapshotCard, SnapshotLine } from "@/components/snapshot-card";
import {
  isDecidable,
  newestFirst,
  parseSnapshotTab,
  type SnapshotTab,
} from "@/lib/snapshot-roles";

/** What every section of one marketplace reads: the marketplace and its snapshots, sorted once. */
type MarketplaceContext = {
  marketplace: MarketplaceView;
  snapshots: Snapshot[];
  awaiting: Snapshot[];
  servedSha: string | null;
};

function useMarketplaceContext() {
  return useOutletContext<MarketplaceContext>();
}

/**
 * This marketplace's slice of the append-only ledger: every facade fetch and administrative
 * action recorded against it, newest first, with the same verdict colouring the audit page
 * and the marketplace row use. It answers "what has happened to this marketplace" without
 * making the reader scan the whole ledger and pick its name out by eye (#221/#224).
 *
 * @Requirements GW_AUDIT_0002, GW_INGEST_0007
 */
function MarketplaceAudit({ name }: { name: string }) {
  const audit = useAudit();
  const rows = (audit.data ?? []).filter((row) => row.marketplace === name).slice().reverse();

  return (
    <div className="space-y-3">
      <h2 className="text-lg font-semibold">Audit log</h2>
      <p className="text-sm text-muted-foreground">
        Ledger entries recorded against this marketplace.{" "}
        <Link to="/audit" className="text-primary hover:underline">
          See the full ledger
        </Link>
        .
      </p>
      {audit.isLoading ? <p className="text-sm text-muted-foreground">Loading…</p> : null}
      {audit.isError ? (
        <p role="alert" className="text-sm text-destructive">
          {audit.error.message}
        </p>
      ) : null}
      {!audit.isLoading && rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          Nothing recorded against this marketplace yet.
        </p>
      ) : null}
      {rows.length > 0 ? (
        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Status</TableHead>
                <TableHead>When</TableHead>
                <TableHead>Event</TableHead>
                <TableHead>Principal</TableHead>
                <TableHead>Commit</TableHead>
                <TableHead>Detail</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row, index) => {
                const sha = typeof row.sha === "string" ? row.sha : "";
                return (
                  <TableRow key={index} className={auditRowClass(row)}>
                    <TableCell>
                      <AuditStatusBadge status={auditStatus(row)} />
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-xs text-muted-foreground">
                      <Timestamp value={row.ts as string | undefined} />
                    </TableCell>
                    <TableCell className="font-mono text-xs">{String(row.event ?? "—")}</TableCell>
                    <TableCell className="text-xs">{String(row.principal ?? "—")}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">
                      {sha ? sha.slice(0, 12) : "—"}
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground">
                      {row.detail ? String(row.detail) : "—"}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      ) : null}
    </div>
  );
}

/**
 * The open-one-at-a-time snapshot list both Review and Snapshots render. The open snapshot, its
 * tab and its file are in the address (`?snapshot=&tab=&path=`), so a link to the evidence
 * restores it (GW_INGEST_0032).
 */
function useSnapshotAddress() {
  const [params, setParams] = useSearchParams();
  const tab = parseSnapshotTab(params.get("tab"));
  const path = params.get("path");
  const addressedId = params.get("snapshot") === null ? null : Number(params.get("snapshot"));
  // Each move names the snapshot explicitly, so the address never depends on which one happens
  // to be newest when the link is opened.
  const openSnapshot = (id: number) => setParams({ snapshot: String(id) });
  const setTab = (id: number, next: SnapshotTab) =>
    setParams(
      next === "contents" && path
        ? { snapshot: String(id), tab: next, path }
        : { snapshot: String(id), tab: next },
      { replace: true },
    );
  // Pushed, not replaced: back and forward walk the files the reviewer visited.
  const setPath = (id: number, next: string) =>
    setParams({ snapshot: String(id), tab: "contents", path: next });
  return { tab, path, addressedId, openSnapshot, setTab, setPath };
}

function SnapshotItem({
  snapshot,
  open,
  others,
  showDelta,
  address,
}: {
  snapshot: Snapshot;
  open: boolean;
  others: Snapshot[];
  showDelta: boolean;
  address: ReturnType<typeof useSnapshotAddress>;
}) {
  const id = snapshot.id ?? 0;
  // A snapshot deleted or restored from here changes role; pinning it keeps it in view.
  const pin = () => address.openSnapshot(id);
  return open ? (
    <li>
      <SnapshotCard
        snapshot={snapshot}
        tab={address.tab}
        path={address.path}
        onTab={(next) => address.setTab(id, next)}
        onPath={(next) => address.setPath(id, next)}
        others={others}
        onRetentionChanged={pin}
      />
    </li>
  ) : (
    <SnapshotLine snapshot={snapshot} onOpen={pin} showDelta={showDelta} onRetentionChanged={pin} />
  );
}

/**
 * Review, the marketplace's default section: what awaits a decision, newest first, the newest
 * open. It is the only section with a pending action, so it is where a marketplace opens.
 * An address naming a snapshot that is not awaiting — an older link to a served one — opens it
 * here rather than dropping it.
 *
 * @Requirements GW_INGEST_0007
 */
export function MarketplaceReviewPage() {
  const { marketplace, snapshots, awaiting } = useMarketplaceContext();
  const address = useSnapshotAddress();
  const addressed = snapshots.find((s) => s.id === address.addressedId);
  const elsewhere = addressed && !awaiting.includes(addressed) ? addressed : undefined;
  const open = addressed ?? awaiting[0];
  const base = `/marketplaces/${marketplace.name}`;

  return (
    <div className="space-y-6">
      {elsewhere ? (
        <section aria-labelledby="addressed-heading" className="space-y-3">
          <h2 id="addressed-heading" className="text-lg font-semibold">Linked snapshot</h2>
          <p className="text-sm text-muted-foreground">
            This snapshot is not awaiting a decision.{" "}
            <Link to={`${base}/snapshots?snapshot=${elsewhere.id}`} className="text-primary hover:underline">
              See it among the snapshots
            </Link>
            .
          </p>
          <ul className="space-y-2">
            <SnapshotItem snapshot={elsewhere} open others={awaiting} showDelta={false} address={address} />
          </ul>
        </section>
      ) : null}
      <section aria-labelledby="awaiting-heading" className="space-y-3">
        <h2 id="awaiting-heading" className="text-lg font-semibold">
          Awaiting decision{awaiting.length > 0 ? ` (${awaiting.length})` : ""}
        </h2>
        {awaiting.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            Nothing awaits a decision.{" "}
            {snapshots.length === 0
              ? "Ingest to fetch the upstream default branch."
              : <Link to={`${base}/snapshots`} className="text-primary hover:underline">See every snapshot</Link>}
          </p>
        ) : (
          <ul className="space-y-2">
            {awaiting.map((snapshot) => (
              <SnapshotItem
                key={snapshot.id}
                snapshot={snapshot}
                open={snapshot === open}
                others={awaiting.filter((other) => other !== snapshot)}
                showDelta
                address={address}
              />
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

/**
 * Snapshots: what is served, then every earlier snapshot, one open at a time. History is the
 * point of this section, so nothing is collapsed behind a count; each unopened snapshot is one
 * line, which is what keeps the page from growing with the ingest count.
 *
 * @Requirements GW_INGEST_0007, GW_INGEST_0033
 */
export function MarketplaceSnapshotsPage() {
  const { marketplace, snapshots, awaiting, servedSha } = useMarketplaceContext();
  const address = useSnapshotAddress();
  // What the facade answers with, read from the served reference — not "the newest approved",
  // which is wrong exactly after a withdrawal leaves an approved record serving nothing.
  const served =
    servedSha !== null
      ? snapshots.find((s) => s.sha === servedSha && s.state === "approved" && !s.deletedAt)
      : undefined;
  const earlier = snapshots.filter((s) => !isDecidable(s) && s !== served);
  const approvedButDark =
    servedSha === null && snapshots.some((s) => s.state === "approved" && !s.deletedAt);
  const addressed = snapshots.find((s) => s.id === address.addressedId);
  const open = addressed ?? served;
  const item = (snapshot: Snapshot) => (
    <SnapshotItem
      key={snapshot.id}
      snapshot={snapshot}
      open={snapshot === open}
      others={awaiting}
      showDelta={false}
      address={address}
    />
  );

  if (snapshots.length === 0)
    return <p className="text-sm text-muted-foreground">No snapshots yet.</p>;

  return (
    <div className="space-y-6">
      {awaiting.length > 0 ? (
        <p className="text-sm text-muted-foreground">
          {awaiting.length} awaiting a decision —{" "}
          <Link to={`/marketplaces/${marketplace.name}`} className="text-primary hover:underline">
            review
          </Link>
          .
        </p>
      ) : null}
      <section aria-labelledby="serving-heading" className="space-y-3">
        <h2 id="serving-heading" className="text-lg font-semibold">Serving</h2>
        {served ? (
          <ul className="space-y-2">{item(served)}</ul>
        ) : servedSha !== null ? (
          <p className="text-sm text-muted-foreground">
            The facade serves <span className="font-mono text-foreground">{servedSha.slice(0, 12)}</span>.
          </p>
        ) : (
          <p className="text-sm text-muted-foreground" data-testid="serving-nothing">
            Nothing is served.
            {approvedButDark
              ? " A snapshot is still recorded approved, but it was withdrawn from the facade — approving is what serves content again."
              : ""}
          </p>
        )}
      </section>
      {earlier.length > 0 ? (
        <section aria-labelledby="earlier-heading" className="space-y-3">
          <h2 id="earlier-heading" className="text-lg font-semibold">
            Earlier snapshots ({earlier.length})
          </h2>
          <ul className="space-y-2">{earlier.map(item)}</ul>
        </section>
      ) : null}
    </div>
  );
}

/** Activity: this marketplace's slice of the ledger. */
export function MarketplaceActivityPage() {
  const { marketplace } = useMarketplaceContext();
  return <MarketplaceAudit name={marketplace.name ?? ""} />;
}

/**
 * Settings: what was registered, and — for an administrator only — the vetting chain and
 * removal. Neither is shown to marketplace-scoped approvers, and the server refuses both
 * independently.
 */
export function MarketplaceSettingsPage() {
  const { marketplace } = useMarketplaceContext();
  const isAdmin = useIsAdmin();
  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>Upstream</CardTitle>
          <CardDescription>Forge metadata captured at registration (best effort).</CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
            <dt className="font-medium">Clone URL</dt>
            <dd className="font-mono text-xs break-all">{marketplace.url ?? "—"}</dd>
            <dt className="font-medium">Forge</dt>
            <dd>{marketplace.forge ?? "—"}</dd>
            <dt className="font-medium">Project</dt>
            <dd>{marketplace.forgeProject ?? "—"}</dd>
            <dt className="font-medium">Description</dt>
            <dd>{marketplace.description ?? "—"}</dd>
            <dt className="font-medium">Last upstream update</dt>
            <dd><Timestamp value={marketplace.upstreamUpdatedAt} /></dd>
            <dt className="font-medium">Last ingest</dt>
            <dd>
              {marketplace.lastIngestOutcome ? (
                <>
                  {marketplace.lastIngestOutcome} · <Timestamp value={marketplace.lastIngestAt} />
                </>
              ) : (
                "never"
              )}
            </dd>
            <dt className="font-medium">Registered</dt>
            <dd><Timestamp value={marketplace.createdAt} /></dd>
            {/* Who chose this upstream is provenance, and the four-eyes rule reads it (GW_APPROVAL_0010). */}
            <dt className="font-medium">Registered by</dt>
            <dd>{marketplace.registeredBy ?? "—"}</dd>
          </dl>
        </CardContent>
      </Card>
      {isAdmin ? <MarketplaceVettingChain marketplace={marketplace.name ?? ""} /> : null}
      {isAdmin ? <RemoveMarketplace name={marketplace.name ?? ""} /> : null}
    </div>
  );
}

/**
 * One marketplace: a header present on every section — its name, source, what the facade
 * serves, and the marketplace's own actions — over the section the address names. The sections
 * are listed in the sidebar beneath the marketplace (see AppLayout).
 *
 * Connect a client is a header action rather than the page's leading panel: it is a step each
 * consumer takes once, and leading with it pushed the reviewer's daily work below the fold.
 *
 * @Requirements GW_INGEST_0007, GW_AUTH_0043, GW_INGEST_0033, GW_INGEST_0039
 */
export function MarketplaceLayout() {
  const { name } = useParams<{ name: string }>();
  const marketplaces = useMarketplaces();
  const ingest = useIngest();
  const navigate = useNavigate();
  const [wizardOpen, setWizardOpen] = useState(false);
  const marketplace = marketplaces.data?.find((m) => m.name === name);

  if (marketplaces.isLoading) return <p>Loading…</p>;
  if (marketplaces.isError)
    return (
      <p role="alert" className="text-sm text-destructive">
        {marketplaces.error.message}
      </p>
    );
  if (!marketplace)
    return (
      <div className="space-y-4">
        <p role="alert" className="text-sm text-destructive">
          Marketplace '{name}' not found.
        </p>
        <Button variant="outline" render={<Link to="/marketplaces">Back to marketplaces</Link>} />
      </div>
    );

  const snapshots = [...(marketplace.snapshots ?? [])].sort(newestFirst);
  const context: MarketplaceContext = {
    marketplace,
    snapshots,
    awaiting: snapshots.filter(isDecidable),
    servedSha: marketplace.servedSha ?? null,
  };
  const serving = context.servedSha !== null;

  const runIngest = () =>
    ingest.mutate(marketplace.name ?? "", {
      // Open what arrived on Review, rather than announce it in a toast and leave it to be found.
      onSuccess: (snapshot) => {
        toast.success(`Snapshot ${snapshot.sha?.slice(0, 12)} is ${snapshot.state}`);
        void navigate(`/marketplaces/${marketplace.name}?snapshot=${snapshot.id}`);
      },
      onError: (error) => toast.error(error.message),
    });

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
        <div className="min-w-0 space-y-1">
          <h1 className="text-2xl font-semibold">{marketplace.name}</h1>
          <p className="break-all text-sm text-muted-foreground">{marketplace.url}</p>
          {/* The page-level half of GW_AUTH_0043: a clone of a marketplace that serves nothing is
              answered with 404, and saying so here is what stops that reading as an outage. */}
          <p data-testid="marketplace-served-status" role="status" className="text-sm">
            {serving ? (
              <>
                <span className="text-muted-foreground">Serving </span>
                <span className="font-mono">{context.servedSha!.slice(0, 12)}</span>
              </>
            ) : (
              <span className="text-muted-foreground">
                Not served — a clone is answered with <span className="font-mono">404</span> until
                a snapshot is approved.
              </span>
            )}
          </p>
          {/* GW_INGEST_0039: an automated ingest has nobody waiting for its response, so a failure
              is stated where the marketplace is read — when, and the reason with its next step. */}
          {marketplace.lastIngestOutcome === "failed" ? (
            <p
              data-testid="marketplace-last-ingest-failed"
              role="alert"
              className="break-words text-sm text-destructive"
            >
              Last ingest failed <Timestamp value={marketplace.lastIngestAt} relative />:{" "}
              {marketplace.lastIngestReason}
            </p>
          ) : null}
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            onClick={runIngest}
            disabled={ingest.isPending}
            aria-label={`Ingest ${marketplace.name}`}
          >
            {ingest.isPending ? "Ingesting…" : "Ingest"}
          </Button>
          <Button variant="outline" onClick={() => setWizardOpen(true)} aria-expanded={wizardOpen}>
            Connect a client
          </Button>
        </div>
      </header>
      {/* Unmounted when closed: a token minted inside lives only while the wizard is open. */}
      {wizardOpen ? (
        <SetupWizard
          marketplace={marketplace.name ?? ""}
          serving={serving}
          onClose={() => setWizardOpen(false)}
        />
      ) : null}
      <Outlet context={context} />
    </div>
  );
}

import { ArrowLeft, ChevronRight } from "lucide-react";
import { useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import {
  useAudit,
  useIsAdmin,
  useMarketplaces,
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
import { HeldNotice, SetupWizard } from "@/components/setup-wizard";
import { SnapshotCard, SnapshotLine } from "@/components/snapshot-card";
import { isDecidable, parseSnapshotTab, type SnapshotTab } from "@/lib/snapshot-roles";

/** Newest first: ingestion time, then id for two ingests in the same instant. */
function newestFirst(a: Snapshot, b: Snapshot): number {
  return (b.createdAt ?? "").localeCompare(a.createdAt ?? "") || (b.id ?? 0) - (a.id ?? 0);
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
 * Marketplace detail: the lead panel, forge metadata, and the snapshots — grouped by what they are
 * for rather than listed alike. What awaits a decision comes first, because it is the only thing
 * on the page with a pending action; what is served is one line; everything else is a count.
 * Exactly one snapshot is open at a time, so page length does not grow with ingest count.
 *
 * The open snapshot, its tab and its file are in the address (`?snapshot=&tab=&path=`), so a
 * link to the evidence restores it.
 *
 * @Requirements GW_INGEST_0007, GW_INGEST_0032, GW_INGEST_0033
 */
export function MarketplaceDetailPage() {
  const { name } = useParams<{ name: string }>();
  const marketplaces = useMarketplaces();
  const [params, setParams] = useSearchParams();
  const [wizardOpen, setWizardOpen] = useState(false);
  const [earlierShown, setEarlierShown] = useState(false);
  const isAdmin = useIsAdmin();
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

  const snapshots = [...(marketplace.snapshots ?? [])].sort(newestFirst);
  // What the facade answers with, read from the served reference — not "the newest approved",
  // which is wrong exactly after a withdrawal leaves an approved record serving nothing.
  const servedSha = marketplace.servedSha ?? null;
  const serving = servedSha !== null;
  const awaiting = snapshots.filter(isDecidable);
  const served = serving
    ? snapshots.find((s) => s.sha === servedSha && s.state === "approved" && !s.deletedAt)
    : undefined;
  const earlier = snapshots.filter((s) => !isDecidable(s) && s !== served);
  const approvedButDark = !serving && snapshots.some((s) => s.state === "approved" && !s.deletedAt);

  const addressed = snapshots.find((s) => s.id === Number(params.get("snapshot")));
  const open = addressed ?? awaiting[0] ?? served;
  const tab = parseSnapshotTab(params.get("tab"));
  const path = params.get("path");

  // Each move names the snapshot explicitly, so the address never depends on which one happens
  // to be newest when the link is opened.
  const openSnapshot = (id: number) => setParams({ snapshot: String(id) });
  const setTab = (id: number, next: SnapshotTab) =>
    setParams(
      next === "contents" && path ? { snapshot: String(id), tab: next, path } : { snapshot: String(id), tab: next },
      { replace: true },
    );
  // Pushed, not replaced: back and forward walk the files the reviewer visited.
  const setPath = (id: number, next: string) =>
    setParams({ snapshot: String(id), tab: "contents", path: next });

  const render = (snapshot: Snapshot, showDelta: boolean) => {
    const id = snapshot.id ?? 0;
    // A snapshot deleted or restored from here moves section; pinning it keeps it in view.
    const pin = () => openSnapshot(id);
    return snapshot === open ? (
      <li key={id}>
        <SnapshotCard
          snapshot={snapshot}
          tab={tab}
          path={path}
          onTab={(next) => setTab(id, next)}
          onPath={(next) => setPath(id, next)}
          others={awaiting.filter((other) => other !== snapshot)}
          onRetentionChanged={pin}
        />
      </li>
    ) : (
      <SnapshotLine
        key={id}
        snapshot={snapshot}
        onOpen={pin}
        showDelta={showDelta}
        onRetentionChanged={pin}
      />
    );
  };

  const earlierOpen = earlierShown || (open !== undefined && earlier.includes(open));

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
      {/* Unmounted when closed: a token minted inside lives only while the wizard is open. */}
      {wizardOpen ? (
        <SetupWizard
          marketplace={marketplace.name ?? ""}
          serving={serving}
          onClose={() => setWizardOpen(false)}
        />
      ) : null}

      {/*
        The lead panel (GW_AUTH_0043). First on the page, above the upstream metadata, because a
        consumer's question on a marketplace that is serving is "how do I use this?" and the
        answer used to be a control competing with the page heading. When nothing is served yet,
        the same slot answers the question the page could not answer at all: why a clone is
        refused with 404.
      */}
      <Card data-testid="setup-lead">
        <CardHeader>
          <CardTitle>{serving ? "Use this marketplace" : "Not being served yet"}</CardTitle>
          <CardDescription>
            {serving
              ? "An approved snapshot is being served. Set up a client against it in one step."
              : "No snapshot is being served, so the facade has nothing to answer a clone with for this marketplace."}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {serving ? null : <HeldNotice marketplace={marketplace.name ?? ""} />}
          <Button onClick={() => setWizardOpen(true)}>Set up a client</Button>
        </CardContent>
      </Card>

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
            {/* Who chose this upstream is provenance, and the four-eyes rule reads it (GW_APPROVAL_0010). */}
            <dt className="font-medium">Registered by</dt>
            <dd>{marketplace.registeredBy ?? "—"}</dd>
          </dl>
        </CardContent>
      </Card>

      {/* Above the snapshots whose verdicts it explains, and only for a session that holds the
          administrative role — the vetter settings are not shown to marketplace-scoped
          approvers, and the server refuses the read independently. */}
      {isAdmin ? <MarketplaceVettingChain marketplace={marketplace.name ?? ""} /> : null}

      {snapshots.length === 0 ? (
        <section aria-labelledby="snapshots-heading" className="space-y-3">
          <h2 id="snapshots-heading" className="text-lg font-semibold">Snapshots</h2>
          <p className="text-sm text-muted-foreground">No snapshots yet.</p>
        </section>
      ) : (
        <>
          <section aria-labelledby="awaiting-heading" className="space-y-3">
            <h2 id="awaiting-heading" className="text-lg font-semibold">
              Awaiting decision{awaiting.length > 0 ? ` (${awaiting.length})` : ""}
            </h2>
            {awaiting.length === 0 ? (
              <p className="text-sm text-muted-foreground">Nothing awaits a decision.</p>
            ) : (
              <ul className="space-y-2">{awaiting.map((snapshot) => render(snapshot, true))}</ul>
            )}
          </section>

          <section aria-labelledby="serving-heading" className="space-y-3">
            <h2 id="serving-heading" className="text-lg font-semibold">Serving</h2>
            {served ? (
              <ul className="space-y-2">{render(served, false)}</ul>
            ) : serving ? (
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
                <Button
                  variant="ghost"
                  className="-ml-2 px-2 text-lg font-semibold"
                  aria-expanded={earlierOpen}
                  onClick={() => setEarlierShown(!earlierOpen)}
                >
                  <ChevronRight
                    className={earlierOpen ? "size-4 rotate-90 transition-transform" : "size-4 transition-transform"}
                    aria-hidden
                  />
                  Earlier snapshots ({earlier.length})
                </Button>
              </h2>
              {earlierOpen ? (
                <ul className="space-y-2">{earlier.map((snapshot) => render(snapshot, false))}</ul>
              ) : null}
            </section>
          ) : null}
        </>
      )}

      <MarketplaceAudit name={marketplace.name ?? ""} />
    </div>
  );
}

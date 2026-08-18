import { Store } from "lucide-react";
import { useState } from "react";
import {
  useAdoption,
  useStaleness,
  type MarketplaceAdoption,
  type StaleIdentity,
} from "@/api/queries";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

const WINDOWS = [7, 30, 90] as const;

function shortSha(sha: string | undefined): string {
  return sha ? sha.slice(0, 12) : "—";
}

// Raw ISO-8601, per the portal-wide convention: timestamps are never localized.
function when(value: string | undefined): string {
  return value ?? "—";
}

function StatChip({ label, value }: { label: string; value: string | number }) {
  return (
    <span className="rounded-md border bg-muted px-2 py-0.5 text-xs">
      <span className="font-semibold">{value}</span> {label}
    </span>
  );
}

/**
 * One marketplace's adoption over the window: header with serving state, stat chips, and the
 * per-snapshot-SHA breakdown with the served tip marked current.
 *
 * @Requirements GW_0075
 */
export function AdoptionMarketplaceCard({ entry }: { entry: MarketplaceAdoption }) {
  return (
    <div className="rounded-lg border p-4">
      <div className="flex flex-wrap items-center gap-3">
        <Store className="size-4 text-primary" aria-hidden />
        <span className="font-medium">{entry.marketplace}</span>
        {entry.servedSha ? (
          <Badge>serving</Badge>
        ) : (
          <Badge variant="secondary">not serving</Badge>
        )}
        <span className="ml-auto flex flex-wrap gap-2">
          <StatChip value={entry.fetches ?? 0} label="fetches" />
          <StatChip value={entry.identities ?? 0} label="identities" />
          <StatChip value={when(entry.lastFetch)} label="last fetch" />
        </span>
      </div>
      {entry.snapshots && entry.snapshots.length > 0 ? (
        <Table className="mt-3">
          <TableHeader>
            <TableRow>
              <TableHead>Snapshot SHA</TableHead>
              <TableHead>Fetches</TableHead>
              <TableHead>Identities</TableHead>
              <TableHead>Last fetch</TableHead>
              <TableHead>Tip</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {entry.snapshots.map((snapshot) => (
              <TableRow key={snapshot.sha}>
                <TableCell className="font-mono text-xs" title={snapshot.sha}>
                  {shortSha(snapshot.sha)}
                </TableCell>
                <TableCell>{snapshot.fetches}</TableCell>
                <TableCell>{snapshot.identities}</TableCell>
                <TableCell className="text-xs">{when(snapshot.lastFetch)}</TableCell>
                <TableCell>
                  {snapshot.current ? (
                    <Badge>current</Badge>
                  ) : (
                    <Badge variant="secondary">superseded</Badge>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : null}
    </div>
  );
}

/**
 * Stale identities: everyone whose most recent fetch is not what the marketplace serves now. A
 * dash in the served-tip column means the marketplace stopped serving entirely — the identity
 * holds retracted content.
 *
 * @Requirements GW_0076
 */
export function StalenessTable({ entries }: { entries: StaleIdentity[] }) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Identity</TableHead>
          <TableHead>Marketplace</TableHead>
          <TableHead>Last received</TableHead>
          <TableHead>Served tip</TableHead>
          <TableHead>Last fetch</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {entries.map((entry) => (
          <TableRow key={`${entry.principal}-${entry.marketplace}`}>
            <TableCell>{entry.principal}</TableCell>
            <TableCell>{entry.marketplace}</TableCell>
            <TableCell className="font-mono text-xs" title={entry.sha}>
              {shortSha(entry.sha)}
            </TableCell>
            <TableCell className="font-mono text-xs" title={entry.servedSha ?? undefined}>
              {entry.servedSha ? (
                shortSha(entry.servedSha)
              ) : (
                <Badge variant="destructive">not serving</Badge>
              )}
            </TableCell>
            <TableCell className="text-xs">{when(entry.lastFetch)}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

/**
 * Adoption dashboard off the append-only fetch ledger: who fetched what, how much, over a
 * selectable window, and who is not on the served tip anymore. Read-only — the reports state
 * facts, and attribution is by identity as the ledger records it (there is no team concept).
 *
 * @Requirements GW_0075, GW_0076, GW_0078
 */
export function AdoptionPage() {
  const [days, setDays] = useState<number>(30);
  const adoption = useAdoption(days);
  const staleness = useStaleness();
  const entries = adoption.data ?? [];
  const stale = staleness.data ?? [];
  const totalFetches = entries.reduce((sum, entry) => sum + (entry.fetches ?? 0), 0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Adoption</h1>
        <p className="text-sm text-muted-foreground">
          Who fetches what through the facade, aggregated from the append-only ledger, and which
          identities are not on the served tip. Attribution is by authenticated identity — the
          gateway has no team concept.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <div role="group" aria-label="Report window" className="flex gap-1">
          {WINDOWS.map((window) => (
            <Button
              key={window}
              size="sm"
              variant={window === days ? "default" : "outline"}
              aria-pressed={window === days}
              onClick={() => setDays(window)}
            >
              {window} days
            </Button>
          ))}
        </div>
        {adoption.data ? (
          <span className="flex flex-wrap gap-2">
            <StatChip value={totalFetches} label="fetches" />
            <StatChip value={entries.length} label="marketplaces fetched" />
            <StatChip value={stale.length} label="stale identities" />
          </span>
        ) : null}
      </div>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">Adoption by marketplace</h2>
        {adoption.isLoading ? <p>Loading…</p> : null}
        {adoption.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {adoption.error.message}
          </p>
        ) : null}
        {adoption.data && entries.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No fetches in the last {days} days. Adoption appears once content is fetched through
            the facade.
          </p>
        ) : null}
        {entries.map((entry) => (
          <AdoptionMarketplaceCard key={entry.marketplace} entry={entry} />
        ))}
      </section>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">Stale identities</h2>
        <p className="text-sm text-muted-foreground">
          Identities whose most recent fetch of a marketplace is not its currently served tip. An
          identity may be pinned on purpose — this table states facts, not verdicts.
        </p>
        {staleness.isLoading ? <p>Loading…</p> : null}
        {staleness.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {staleness.error.message}
          </p>
        ) : null}
        {staleness.data && stale.length === 0 ? (
          <p className="text-sm text-muted-foreground">Every identity is on the served tip.</p>
        ) : null}
        {stale.length > 0 ? <StalenessTable entries={stale} /> : null}
      </section>
    </div>
  );
}

import type { ReactNode } from "react";
import { Store } from "lucide-react";
import { useState } from "react";
import {
  useAdoption,
  usePresence,
  useStaleness,
  type MarketplaceAdoption,
  type PresenceReport,
  type StaleIdentity,
} from "@/api/queries";
import { SegmentedGroup, type SegmentedOption } from "@/components/segmented-group";
import { Timestamp } from "@/components/timestamp";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

const WINDOWS: readonly SegmentedOption<number>[] = [
  { value: 7, label: "7 days" },
  { value: 30, label: "30 days" },
  { value: 90, label: "90 days" },
] as const;

function shortSha(sha: string | undefined): string {
  return sha ? sha.slice(0, 12) : "—";
}


function StatChip({ label, value }: { label: string; value: ReactNode }) {
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
 * @Requirements GW_OBSERVABILITY_0001
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
          <StatChip value={<Timestamp value={entry.lastFetch} />} label="last fetch" />
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
                <TableCell className="text-xs"><Timestamp value={snapshot.lastFetch} /></TableCell>
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
 * @Requirements GW_OBSERVABILITY_0002
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
            <TableCell className="text-xs"><Timestamp value={entry.lastFetch} /></TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

/**
 * Skill-level presence: per served skill, the identities whose latest fetch holds it and the
 * snapshots that delivered it. The report's own statement sits above the table, because the
 * counts are uniform within a snapshot and read as usage otherwise. Unresolvable snapshots are
 * listed, never dropped: their holders may hold any skill.
 *
 * @Requirements GW_OBSERVABILITY_0006, GW_OBSERVABILITY_0007
 */
export function PresenceTable({ report }: { report: PresenceReport }) {
  const skills = report.skills ?? [];
  const unresolved = report.unresolved ?? [];
  return (
    <div className="space-y-3">
      <p className="text-sm text-muted-foreground">{report.statement}</p>
      {skills.length > 0 ? (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Skill</TableHead>
              <TableHead>Plugin</TableHead>
              <TableHead>Marketplace</TableHead>
              <TableHead>Identities holding</TableHead>
              <TableHead>Snapshots delivering</TableHead>
              <TableHead>First delivered</TableHead>
              <TableHead>Last delivered</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {skills.map((skill) => (
              <TableRow key={`${skill.marketplace}/${skill.plugin}/${skill.skill}`}>
                <TableCell>
                  <div className="font-medium">{skill.skill}</div>
                  {skill.path ? (
                    <div className="font-mono text-xs text-muted-foreground">{skill.path}</div>
                  ) : null}
                </TableCell>
                <TableCell>{skill.plugin ?? "—"}</TableCell>
                <TableCell>{skill.marketplace}</TableCell>
                <TableCell>{skill.identitiesHolding}</TableCell>
                <TableCell>{skill.snapshotsDelivering}</TableCell>
                <TableCell className="text-xs"><Timestamp value={skill.firstDelivered} /></TableCell>
                <TableCell className="text-xs"><Timestamp value={skill.lastDelivered} /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : null}
      {unresolved.length > 0 ? (
        <div role="note" className="rounded-lg border p-4 text-sm">
          <p className="font-medium">Content not resolvable</p>
          <p className="text-muted-foreground">
            These delivered snapshots can no longer be read, so the skills they carried are unknown.
            Their holders may hold any skill above.
          </p>
          <ul className="mt-2 space-y-1">
            {unresolved.map((entry) => (
              <li key={`${entry.marketplace}/${entry.sha}`} className="flex flex-wrap gap-2">
                <span>{entry.marketplace}</span>
                <span className="font-mono text-xs" title={entry.sha}>
                  {shortSha(entry.sha)}
                </span>
                <StatChip value={entry.identitiesHolding ?? 0} label="identities holding" />
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

/**
 * Adoption dashboard off the append-only fetch ledger: who fetched what, how much, over a
 * selectable window, and who is not on the served tip anymore. Read-only — the reports state
 * facts, and attribution is by identity as the ledger records it (there is no team concept).
 *
 * @Requirements GW_OBSERVABILITY_0001, GW_OBSERVABILITY_0002, GW_OBSERVABILITY_0004, GW_OBSERVABILITY_0006
 */
export function AdoptionPage() {
  const [days, setDays] = useState<number>(30);
  const adoption = useAdoption(days);
  const staleness = useStaleness();
  const presence = usePresence();
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
        <SegmentedGroup label="Report window" hideLabel value={days} options={WINDOWS} onChange={setDays} />
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

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">Skill presence</h2>
        <p className="text-sm text-muted-foreground">
          Every skill in a snapshot the facade delivered, with the identities whose latest fetch
          holds it. Not bound to the report window: an old install still holds what it fetched.
        </p>
        {presence.isLoading ? <p>Loading…</p> : null}
        {presence.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {presence.error.message}
          </p>
        ) : null}
        {presence.data &&
        (presence.data.skills ?? []).length === 0 &&
        (presence.data.unresolved ?? []).length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No skills delivered yet. Presence appears once content is fetched through the facade.
          </p>
        ) : null}
        {presence.data &&
        ((presence.data.skills ?? []).length > 0 || (presence.data.unresolved ?? []).length > 0) ? (
          <PresenceTable report={presence.data} />
        ) : null}
      </section>
    </div>
  );
}

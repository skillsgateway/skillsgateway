import { useProvenance } from "@/api/queries";
import { Timestamp } from "@/components/timestamp";

/**
 * What was served, from where, and who approved it — and, for a snapshot with resolved external
 * plugin sources, the closure: each external plugin with the URL it was fetched through and the
 * commit it resolved to, as recorded with the snapshot at ingestion.
 *
 * @Requirements GW_INGEST_0030.5
 */
export function ProvenanceDetails({ snapshotId }: { snapshotId: number }) {
  const provenance = useProvenance(snapshotId);
  const p = provenance.data;
  const members = p?.closure?.members ?? [];
  return (
    <div className="space-y-3">
      {provenance.isLoading ? <p>Loading…</p> : null}
      {provenance.isError ? (
        <p role="alert" className="text-sm text-destructive">
          {provenance.error.message}
        </p>
      ) : null}
      {p ? (
        <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="font-medium">Marketplace</dt>
          <dd>{p.marketplace}</dd>
          <dt className="font-medium">Upstream URL</dt>
          <dd className="break-all">{p.upstreamUrl}</dd>
          <dt className="font-medium">Upstream SHA</dt>
          <dd className="font-mono break-all">{p.upstreamSha}</dd>
          <dt className="font-medium">Served SHA</dt>
          <dd className="font-mono break-all">{p.sha}</dd>
          <dt className="font-medium">State</dt>
          <dd>{p.state}</dd>
          <dt className="font-medium">Ingested</dt>
          <dd><Timestamp value={p.ingestedAt} /></dd>
          <dt className="font-medium">Decided by</dt>
          <dd>{p.decidedBy ?? "—"}</dd>
          <dt className="font-medium">Decided at</dt>
          <dd><Timestamp value={p.decidedAt} /></dd>
        </dl>
      ) : null}
      {members.length > 0 ? (
        <section aria-labelledby={`closure-${snapshotId}`} className="text-sm">
          <h3 id={`closure-${snapshotId}`} className="font-medium">
            External plugin sources
          </h3>
          <p className="text-muted-foreground">
            Resolved at ingestion and recorded with the snapshot; the served commit contains
            exactly these.
          </p>
          <ul className="mt-1 space-y-1">
            {members.map((member) => (
              <li key={member.graftPath} className="grid grid-cols-[max-content_1fr] gap-x-4">
                <span className="font-medium">{member.pluginName}</span>
                <span className="break-all">
                  <span>{member.cloneUrl}</span>{" "}
                  <span className="font-mono">{member.resolvedSha}</span>
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}

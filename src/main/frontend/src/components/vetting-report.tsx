import { AlertTriangle, CircleAlert, CircleCheck, CircleHelp, ShieldCheck } from "lucide-react";
import { useSnapshotVetting, type VettingFinding, type VettingVerdict } from "@/api/queries";
import { Badge } from "@/components/ui/badge";

function verdictIcon(state?: string) {
  switch (state) {
    case "PASS":
      return <CircleCheck className="size-4 text-primary" aria-hidden />;
    case "WARN":
      return <AlertTriangle className="size-4 text-primary" aria-hidden />;
    case "FAIL":
    case "ERROR":
      return <CircleAlert className="size-4 text-destructive" aria-hidden />;
    default:
      return <CircleHelp className="size-4 text-muted-foreground" aria-hidden />;
  }
}

function verdictBadge(state?: string) {
  switch (state) {
    case "PASS":
      return <Badge>pass</Badge>;
    case "WARN":
      return <Badge variant="secondary">warn</Badge>;
    case "FAIL":
      return <Badge variant="destructive">fail</Badge>;
    case "ERROR":
      return <Badge variant="destructive">error</Badge>;
    case "PENDING":
      return <Badge variant="outline">pending</Badge>;
    default:
      return <Badge variant="outline">{state ?? "unknown"}</Badge>;
  }
}

/** Outcome of the whole chain. A snapshot with no run at all is blocked, never "unknown". */
export function OutcomeBadge({ outcome }: { outcome?: string }) {
  return outcome === "CLEAR" ? (
    <Badge>vetting clear</Badge>
  ) : (
    <Badge variant="destructive">vetting blocked</Badge>
  );
}

function FindingRow({ finding }: { finding: VettingFinding }) {
  const high = finding.severity === "HIGH" || finding.severity === "CRITICAL";
  return (
    <li className="flex flex-wrap items-baseline gap-2 text-sm">
      <Badge variant={high ? "destructive" : "outline"}>{finding.severity?.toLowerCase()}</Badge>
      <span className="font-mono text-xs">{finding.id}</span>
      <span className="font-mono text-xs text-muted-foreground">{finding.location ?? "—"}</span>
      <span className="text-muted-foreground">{finding.message}</span>
    </li>
  );
}

function VerdictCard({ verdict }: { verdict: VettingVerdict }) {
  const findings = verdict.findings ?? [];
  return (
    <div className="rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        {verdictIcon(verdict.state)}
        <span className="font-medium">{verdict.connector}</span>
        {verdictBadge(verdict.state)}
        {verdict.detail ? (
          <span className="text-xs text-muted-foreground">{verdict.detail}</span>
        ) : null}
      </div>
      {findings.length > 0 ? (
        <ul className="mt-2 space-y-1">
          {findings.map((finding, index) => (
            <FindingRow key={`${finding.id}-${finding.location}-${index}`} finding={finding} />
          ))}
        </ul>
      ) : (
        <p className="mt-1 text-sm text-muted-foreground">Nothing found.</p>
      )}
    </div>
  );
}

/**
 * The reviewer's evidence: what every connector in the chain concluded about this snapshot,
 * and the findings behind it. Rendered before any approve/reject decision.
 *
 * @Requirements GW_0042
 */
export function VettingReport({ snapshotId }: { snapshotId: number }) {
  const vetting = useSnapshotVetting(snapshotId);

  if (vetting.isLoading) return <p className="text-sm text-muted-foreground">Loading verdicts…</p>;
  if (vetting.isError)
    return (
      <p role="alert" className="text-sm text-destructive">
        {vetting.error.message}
      </p>
    );

  const run = vetting.data?.run;
  const verdicts = run?.verdicts ?? [];
  return (
    <section aria-label={`Vetting of snapshot ${snapshotId}`} className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <ShieldCheck className="size-4 text-primary" aria-hidden />
        <span className="font-medium">Vetting</span>
        <OutcomeBadge outcome={vetting.data?.outcome} />
        {run?.overrideBy ? (
          <span className="text-xs text-muted-foreground">
            overridden by {run.overrideBy}: {run.overrideReason}
          </span>
        ) : null}
      </div>
      {verdicts.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          The vetting chain has not run for this snapshot, so it counts as blocked. Approving it
          requires a recorded reason.
        </p>
      ) : (
        <div className="space-y-2">
          {verdicts.map((verdict) => (
            <VerdictCard key={verdict.connector} verdict={verdict} />
          ))}
        </div>
      )}
      {(vetting.data?.connectors ?? []).length > 0 ? (
        <details className="text-xs text-muted-foreground">
          <summary className="cursor-pointer">What these connectors can and cannot see</summary>
          <ul className="mt-2 space-y-1">
            {(vetting.data?.connectors ?? []).map((connector) => (
              <li key={connector.name}>
                <span className="font-mono">{connector.name}</span> — {connector.description}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </section>
  );
}

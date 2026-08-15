import { useState } from "react";
import {
  AlertTriangle,
  CircleAlert,
  CircleCheck,
  CircleHelp,
  ShieldCheck,
  ShieldOff,
} from "lucide-react";
import { toast } from "sonner";
import {
  useCreateWaiver,
  useRevokeWaiver,
  useSnapshotVetting,
  type VettingFinding,
  type VettingVerdict,
  type Waiver,
  type WaiverScope,
  type WaiverSuppression,
} from "@/api/queries";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

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

/**
 * Outcome of the whole chain. A snapshot with no run at all is blocked, never "unknown", and an
 * outcome that only clears because of a waiver says so: an accepted risk must never read as a
 * clean chain.
 */
export function OutcomeBadge({ outcome }: { outcome?: string }) {
  if (outcome === "CLEAR") return <Badge>vetting clear</Badge>;
  if (outcome === "CLEAR_WITH_WAIVERS")
    return <Badge variant="secondary">vetting clear with waivers</Badge>;
  return <Badge variant="destructive">vetting blocked</Badge>;
}

/** Stable identity of a finding within a run, so a suppression can be matched to its row. */
function findingKey(connector: string | undefined, finding: VettingFinding) {
  return `${connector ?? ""}|${finding.id ?? ""}|${finding.location ?? ""}`;
}

/** The default expiry offered when accepting a risk: near enough to come back around. */
function defaultExpiry() {
  const date = new Date();
  date.setDate(date.getDate() + 30);
  return date.toISOString().slice(0, 10);
}

/**
 * Accepting one finding, inline beside the finding itself. Scope defaults to this snapshot —
 * the tightest option — because a path waiver survives re-ingestion and covers content that does
 * not exist yet.
 */
function WaiveForm({
  snapshotId,
  finding,
  onDone,
  onCancel,
}: {
  snapshotId: number;
  finding: VettingFinding;
  onDone: () => void;
  onCancel: () => void;
}) {
  const create = useCreateWaiver();
  const [scope, setScope] = useState<WaiverScope>("SNAPSHOT");
  const [justification, setJustification] = useState("");
  const [expiresAt, setExpiresAt] = useState(defaultExpiry());
  const path = (finding.location ?? "").replace(/:\d+$/, "");
  const rule = finding.id ?? "";
  const incomplete = justification.trim().length === 0 || expiresAt.length === 0;

  return (
    <div className="mt-2 space-y-2 rounded-md border bg-muted/40 p-3">
      <p className="text-xs text-muted-foreground">
        Accepting <span className="font-mono">{rule}</span>. The acceptance is recorded with your
        identity, and it lapses on the date you choose — there are no unlimited waivers.
      </p>
      <div className="grid gap-2 sm:grid-cols-2">
        <div className="space-y-1">
          <Label htmlFor={`waiver-scope-${snapshotId}-${rule}`}>Scope</Label>
          <select
            id={`waiver-scope-${snapshotId}-${rule}`}
            className="h-9 w-full rounded-md border bg-background px-2 text-sm"
            value={scope}
            onChange={(event) => setScope(event.target.value as WaiverScope)}
          >
            <option value="SNAPSHOT">This snapshot only</option>
            <option value="PATH">This path in the marketplace ({path || "—"})</option>
          </select>
        </div>
        <div className="space-y-1">
          <Label htmlFor={`waiver-expiry-${snapshotId}-${rule}`}>Expires on</Label>
          <Input
            id={`waiver-expiry-${snapshotId}-${rule}`}
            type="date"
            value={expiresAt}
            onChange={(event) => setExpiresAt(event.target.value)}
          />
        </div>
      </div>
      <div className="space-y-1">
        <Label htmlFor={`waiver-justification-${snapshotId}-${rule}`}>Justification</Label>
        <Input
          id={`waiver-justification-${snapshotId}-${rule}`}
          autoComplete="off"
          value={justification}
          onChange={(event) => setJustification(event.target.value)}
          placeholder="Why is this acceptable?"
        />
      </div>
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={create.isPending || incomplete}
          aria-label={`Record waiver for ${rule}`}
          onClick={() =>
            create.mutate(
              {
                snapshotId,
                ruleId: rule,
                scope,
                ...(scope === "PATH" ? { path } : {}),
                justification: justification.trim(),
                // The API takes an instant; a date control gives a day, so it lapses at its end.
                expiresAt: new Date(`${expiresAt}T23:59:59Z`).toISOString(),
              },
              {
                onSuccess: () => {
                  toast.success(`Waiver recorded for ${rule}`);
                  onDone();
                },
                onError: (error) => toast.error(error.message),
              },
            )
          }
        >
          {create.isPending ? "Recording…" : "Record waiver"}
        </Button>
        <Button size="sm" variant="outline" onClick={onCancel} aria-label={`Cancel waiver for ${rule}`}>
          Cancel
        </Button>
      </div>
    </div>
  );
}

function FindingRow({
  snapshotId,
  finding,
  suppression,
}: {
  snapshotId: number;
  finding: VettingFinding;
  suppression?: WaiverSuppression;
}) {
  const [waiving, setWaiving] = useState(false);
  const high = finding.severity === "HIGH" || finding.severity === "CRITICAL";
  const waived = suppression !== undefined;
  return (
    <div className="text-sm">
      <div className="flex flex-wrap items-baseline gap-2">
        <Badge variant={waived ? "outline" : high ? "destructive" : "outline"}>
          {finding.severity?.toLowerCase()}
        </Badge>
        <span className={`font-mono text-xs ${waived ? "line-through" : ""}`}>{finding.id}</span>
        <span className="font-mono text-xs text-muted-foreground">{finding.location ?? "—"}</span>
        <span className="text-muted-foreground">{finding.message}</span>
        {waived ? (
          <Badge variant="secondary">
            waived by {suppression.approvedBy} until{" "}
            {suppression.expiresAt?.slice(0, 10) ?? "—"}
          </Badge>
        ) : high && !waiving ? (
          <Button
            size="sm"
            variant="outline"
            aria-label={`Waive finding ${finding.id}`}
            onClick={() => setWaiving(true)}
          >
            Waive…
          </Button>
        ) : null}
      </div>
      {waiving && !waived ? (
        <WaiveForm
          snapshotId={snapshotId}
          finding={finding}
          onDone={() => setWaiving(false)}
          onCancel={() => setWaiving(false)}
        />
      ) : null}
    </div>
  );
}

function VerdictCard({
  snapshotId,
  verdict,
  suppressions,
}: {
  snapshotId: number;
  verdict: VettingVerdict;
  suppressions: Map<string, WaiverSuppression>;
}) {
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
            <li key={`${finding.id}-${finding.location}-${index}`} className="list-none">
              <FindingRow
                snapshotId={snapshotId}
                finding={finding}
                suppression={suppressions.get(findingKey(verdict.connector, finding))}
              />
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-1 text-sm text-muted-foreground">Nothing found.</p>
      )}
    </div>
  );
}

/** What has been accepted on this marketplace for the rules this run raised, and until when. */
function WaiverList({ waivers }: { waivers: Waiver[] }) {
  const revoke = useRevokeWaiver();
  if (waivers.length === 0) return null;
  return (
    <section aria-label="Waivers" className="space-y-2">
      <div className="flex items-center gap-2">
        <ShieldOff className="size-4 text-muted-foreground" aria-hidden />
        <span className="font-medium">Accepted risks</span>
      </div>
      <ul className="space-y-1">
        {waivers.map((waiver) => (
          <li key={waiver.id} className="flex flex-wrap items-baseline gap-2 text-sm">
            <Badge variant={waiver.active ? "secondary" : "outline"}>
              {waiver.active ? "active" : waiver.revokedAt ? "revoked" : "expired"}
            </Badge>
            <span className="font-mono text-xs">{waiver.ruleId}</span>
            <span className="font-mono text-xs text-muted-foreground">
              {waiver.scope === "SNAPSHOT" ? "snapshot" : "path"}: {waiver.scopeValue}
            </span>
            <span className="text-muted-foreground">{waiver.justification}</span>
            <span className="text-xs text-muted-foreground">
              — {waiver.approvedBy}, until {waiver.expiresAt?.slice(0, 10) ?? "—"}
            </span>
            {waiver.active ? (
              <Button
                size="sm"
                variant="outline"
                disabled={revoke.isPending}
                aria-label={`Revoke waiver ${waiver.id}`}
                onClick={() =>
                  revoke.mutate(waiver.id ?? 0, {
                    onSuccess: () => toast.success(`Waiver ${waiver.id} revoked`),
                    onError: (error) => toast.error(error.message),
                  })
                }
              >
                Revoke
              </Button>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * The reviewer's evidence: what every connector in the chain concluded about this snapshot, the
 * findings behind it, and which of those findings an accepted risk is currently suppressing.
 * Rendered before any approve/reject decision.
 *
 * @Requirements GW_0042, GW_0047
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
  const suppressions = new Map<string, WaiverSuppression>(
    (vetting.data?.suppressed ?? []).map((suppression) => [
      `${suppression.connector ?? ""}|${suppression.ruleId ?? ""}|${suppression.location ?? ""}`,
      suppression,
    ]),
  );
  const uncovered = vetting.data?.uncovered ?? [];
  return (
    <section aria-label={`Vetting of snapshot ${snapshotId}`} className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <ShieldCheck className="size-4 text-primary" aria-hidden />
        <span className="font-medium">Vetting</span>
        <OutcomeBadge outcome={vetting.data?.outcome} />
        {vetting.data?.recordedOutcome === "BLOCKED" &&
        vetting.data?.outcome === "CLEAR_WITH_WAIVERS" ? (
          <span className="text-xs text-muted-foreground">
            the chain objected; active waivers are suppressing what it found
          </span>
        ) : null}
      </div>
      {uncovered.length > 0 ? (
        <p className="text-sm text-muted-foreground">
          Approval is blocked until each of these is waived:{" "}
          {uncovered.map((finding) => finding.ruleId).join(", ")}.
        </p>
      ) : null}
      {verdicts.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          The vetting chain has not run for this snapshot, so it counts as blocked. There is
          nothing to waive: a snapshot with no evidence cannot be approved.
        </p>
      ) : (
        <div className="space-y-2">
          {verdicts.map((verdict) => (
            <VerdictCard
              key={verdict.connector}
              snapshotId={snapshotId}
              verdict={verdict}
              suppressions={suppressions}
            />
          ))}
        </div>
      )}
      <WaiverList waivers={vetting.data?.waivers ?? []} />
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

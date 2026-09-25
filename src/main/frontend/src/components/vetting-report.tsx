import { useState } from "react";
import {
  AlertTriangle,
  CircleAlert,
  CircleCheck,
  CircleHelp,
  CircleSlash,
  ShieldCheck,
  ShieldOff,
} from "lucide-react";
import { toast } from "sonner";
import {
  useCreateWaiver,
  useRevetSnapshot,
  useRevokeWaiver,
  useSnapshotVetting,
  type VettingView,
  type FindingGroup,
  type VettingVerdict,
  type Waiver,
  type WaiverSuppression,
} from "@/api/queries";
import { Timestamp } from "@/components/timestamp";
import { VettingFlow } from "@/components/vetting-flow";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  describeLocations,
  snapshotFlow,
  snapshotHeadline,
  type WaiveTarget,
} from "@/lib/vetting-flow";

function verdictIcon(state?: string) {
  switch (state) {
    case "pass":
      return <CircleCheck className="size-4 text-primary" aria-hidden />;
    case "warn":
      return <AlertTriangle className="size-4 text-primary" aria-hidden />;
    case "fail":
    case "error":
      return <CircleAlert className="size-4 text-destructive" aria-hidden />;
    case "disabled":
      return <CircleSlash className="size-4 text-muted-foreground" aria-hidden />;
    default:
      return <CircleHelp className="size-4 text-muted-foreground" aria-hidden />;
  }
}

function verdictBadge(state?: string) {
  switch (state) {
    case "pass":
      return <Badge>pass</Badge>;
    case "warn":
      return <Badge variant="secondary">warn</Badge>;
    case "fail":
      return <Badge variant="destructive">fail</Badge>;
    case "error":
      return <Badge variant="destructive">error</Badge>;
    case "pending":
      return <Badge variant="outline">pending</Badge>;
    // Not a conclusion the vetter reached: an administrator switched it off for this
    // marketplace, so the chain recorded the skip in its place. It neither clears nor blocks, and
    // it must never read as a pass.
    case "disabled":
      return <Badge variant="outline">disabled</Badge>;
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
  if (outcome === "clear") return <Badge>vetting clear</Badge>;
  if (outcome === "clear_with_waivers")
    return <Badge variant="secondary">vetting clear with waivers</Badge>;
  return <Badge variant="destructive">vetting blocked</Badge>;
}

/** A snapshot's chain outcome, read on its own — for the tables that list snapshots side by side. */
export function SnapshotVettingBadge({ snapshotId }: { snapshotId: number }) {
  const vetting = useSnapshotVetting(snapshotId);
  if (vetting.isLoading) return <span className="text-xs text-muted-foreground">…</span>;
  if (vetting.isError) return <span className="text-xs text-muted-foreground">unavailable</span>;
  return <OutcomeBadge outcome={vetting.data?.outcome} />;
}

/** Stable identity of one finding within a run, so a suppression can be matched to its location. */
function suppressionKey(vetter: string | undefined, rule: string | undefined, location: string | undefined) {
  return `${vetter ?? ""}|${rule ?? ""}|${location ?? ""}`;
}

/** The default expiry offered when accepting a risk: near enough to come back around. */
function defaultExpiry() {
  const date = new Date();
  date.setDate(date.getDate() + 30);
  return date.toISOString().slice(0, 10);
}

type WaiveChoice = "group" | "snapshot" | "path";

/**
 * Accepting one finding group, inline beside it. The default is the narrowest acceptance on
 * offer: the group itself — every location of this identical content in this snapshot, and
 * nothing else (GW_VETTING_0042). The rule across the whole snapshot and a path in the
 * marketplace are the wider choices, spelled out as such.
 *
 * @Requirements GW_VETTING_0010, GW_VETTING_0044
 */
export function WaiveForm({
  snapshotId,
  target,
  onDone,
  onCancel,
  snapshotOnly = false,
}: {
  snapshotId: number;
  target: WaiveTarget;
  onDone: () => void;
  onCancel: () => void;
  /** Offer no path scope — for a rule whose path scope would cover a whole marketplace. */
  snapshotOnly?: boolean;
}) {
  const create = useCreateWaiver();
  const rule = target.ruleId;
  const count = target.locations.length;
  const groupable = Boolean(target.content);
  // A path scope names one path, so it is offered only for a single location.
  const pathable = !snapshotOnly && count === 1;
  const [choice, setChoice] = useState<WaiveChoice>(groupable ? "group" : "snapshot");
  const [justification, setJustification] = useState("");
  const [expiresAt, setExpiresAt] = useState(defaultExpiry());
  const path = (target.locations[0] ?? "").replace(/:\d+$/, "");
  // The API takes an instant; a date control gives a day, so the waiver lapses at its end.
  // This is the exact value posted below, and the exact value WaiverService compares
  // against now — so the client's "is it still in the future?" is the server's own test
  // (a justification is likewise mandatory there, never blank).
  const expiryInstant = new Date(`${expiresAt}T23:59:59Z`).getTime();
  const expiryIsFuture = Number.isFinite(expiryInstant) && expiryInstant > Date.now();
  const incomplete = justification.trim().length === 0 || !expiryIsFuture;
  const idBase = `${snapshotId}-${rule}-${target.content ?? "rule"}-${target.line ?? 0}`;
  const hintId = `waiver-hint-${idBase}`;

  return (
    <div className="mt-2 space-y-2 rounded-md border bg-muted/40 p-3">
      <p className="text-xs text-muted-foreground">
        Accepting <span className="font-mono">{rule}</span>
        {count > 1 ? ` at ${count} locations` : null}. The acceptance is recorded with your identity,
        and it lapses on the date you choose — there are no unlimited waivers.
      </p>
      <div className="grid gap-2 sm:grid-cols-2">
        {/* Full width: the scope options say how far each one reaches, and that text must not be cut. */}
        <div className="space-y-1 sm:col-span-2">
          <Label htmlFor={`waiver-scope-${idBase}`}>Scope</Label>
          <select
            id={`waiver-scope-${idBase}`}
            className="h-9 w-full rounded-md border bg-background px-2 text-sm"
            value={choice}
            onChange={(event) => setChoice(event.target.value as WaiveChoice)}
          >
            {groupable ? (
              <option value="group">
                {count > 1
                  ? `These ${count} identical copies, in this snapshot`
                  : "This finding, in this snapshot"}
              </option>
            ) : null}
            <option value="snapshot">Every {rule} finding in this snapshot</option>
            {pathable ? (
              <option value="path">Every {rule} finding under {path || "—"}, in later snapshots too</option>
            ) : null}
          </select>
        </div>
        <div className="space-y-1">
          <Label htmlFor={`waiver-expiry-${idBase}`}>Expires on</Label>
          <Input
            id={`waiver-expiry-${idBase}`}
            type="date"
            value={expiresAt}
            // The native control refuses a past day for the same reason the server does.
            min={new Date().toISOString().slice(0, 10)}
            aria-invalid={expiryIsFuture ? undefined : true}
            aria-describedby={hintId}
            onChange={(event) => setExpiresAt(event.target.value)}
          />
        </div>
      </div>
      <div className="space-y-1">
        <Label htmlFor={`waiver-justification-${idBase}`}>Justification</Label>
        <Input
          id={`waiver-justification-${idBase}`}
          autoComplete="off"
          value={justification}
          aria-describedby={hintId}
          onChange={(event) => setJustification(event.target.value)}
          placeholder="Why is this acceptable?"
        />
      </div>
      <p id={hintId} className="text-xs text-muted-foreground">
        Record waiver enables once a justification is written and the expiry is a future
        date. Both are mandatory — the gateway refuses an unlimited or unexplained waiver.
      </p>
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
                scope: choice === "path" ? "path" : "snapshot",
                ...(choice === "path" ? { path } : {}),
                ...(choice === "group" ? { content: target.content, line: target.line } : {}),
                justification: justification.trim(),
                expiresAt: new Date(expiryInstant).toISOString(),
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

/**
 * One finding group: a rule on identical content, with every location it occurs at. The copies
 * of a vendored file are one thing to judge, so they are one row with one waive action
 * (GW_VETTING_0041, GW_VETTING_0044).
 */
function GroupRow({
  snapshotId,
  vetter,
  group,
  suppressions,
}: {
  snapshotId: number;
  vetter?: string;
  group: FindingGroup;
  suppressions: Map<string, WaiverSuppression>;
}) {
  const [waiving, setWaiving] = useState(false);
  const rule = group.ruleId ?? "";
  const locations = group.locations ?? [];
  const covering = locations
    .map((location) => suppressions.get(suppressionKey(vetter, rule, location)))
    .filter((suppression): suppression is WaiverSuppression => suppression !== undefined);
  const waived = locations.length > 0 && covering.length === locations.length;
  const high = group.severity === "high" || group.severity === "critical";
  const first = locations[0] ?? "—";
  const rest = locations.slice(1);
  const actionLabel = locations.length > 1 ? `Waive all ${locations.length} locations` : "Waive finding";
  return (
    <div className="text-sm">
      <div className="flex flex-wrap items-baseline gap-2">
        <Badge variant={waived ? "outline" : high ? "destructive" : "outline"}>
          {group.severity?.toLowerCase()}
        </Badge>
        <span className={`font-mono text-xs ${waived ? "line-through" : ""}`}>{group.ruleId}</span>
        <span className="font-mono text-xs break-all text-muted-foreground">{first}</span>
        <span className="text-muted-foreground">{group.message}</span>
        {waived ? (
          <Badge variant="secondary">
            waived by {covering[0]!.approvedBy} until{" "}
            <Timestamp value={covering[0]!.expiresAt} dayOnly />
          </Badge>
        ) : covering.length > 0 ? (
          <Badge variant="outline">
            {covering.length} of {locations.length} waived
          </Badge>
        ) : null}
        {!waived && high && !waiving ? (
          <Button
            size="sm"
            variant="outline"
            aria-label={
              locations.length > 1
                ? `${actionLabel} of ${rule}`
                : `Waive finding ${rule} at ${first}`
            }
            onClick={() => setWaiving(true)}
          >
            {actionLabel}
          </Button>
        ) : null}
      </div>
      {rest.length > 0 ? (
        <details className="mt-1 text-xs text-muted-foreground">
          <summary className="cursor-pointer">
            {rest.length} more {rest.length === 1 ? "copy" : "copies"} of the same content
          </summary>
          <ul className="mt-1 space-y-0.5 font-mono break-all">
            {rest.map((location) => (
              <li key={location}>{location}</li>
            ))}
          </ul>
        </details>
      ) : null}
      {waiving && !waived ? (
        <WaiveForm
          snapshotId={snapshotId}
          target={{
            ruleId: rule,
            locations,
            content: group.content,
            line: group.line,
          }}
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
  const groups = verdict.groups ?? [];
  return (
    <div className="rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        {verdictIcon(verdict.state)}
        <span className="font-medium">{verdict.vetter}</span>
        {verdictBadge(verdict.state)}
        {verdict.detail ? (
          <span className="text-xs text-muted-foreground">{verdict.detail}</span>
        ) : null}
      </div>
      {groups.length > 0 ? (
        <ul className="mt-2 space-y-1">
          {groups.map((group, index) => (
            <li key={`${group.ruleId}-${group.content ?? ""}-${group.locations?.[0] ?? ""}-${index}`} className="list-none">
              <GroupRow
                snapshotId={snapshotId}
                vetter={verdict.vetter}
                group={group}
                suppressions={suppressions}
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
              {waiver.scope === "snapshot" ? "snapshot" : "path"}: {waiver.scopeValue}
            </span>
            <span className="text-muted-foreground">{waiver.justification}</span>
            <span className="text-xs text-muted-foreground">
              — {waiver.approvedBy}, until <Timestamp value={waiver.expiresAt} dayOnly />
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
 * The reviewer's evidence: what every vetter in the chain concluded about this snapshot, the
 * findings behind it, and which of those findings an accepted risk is currently suppressing.
 * Rendered before any approve/reject decision.
 *
 * @Requirements GW_VETTING_0005, GW_VETTING_0010, GW_VETTING_0044, GW_APPROVAL_0022
 */
/**
 * Whether the evidence below was produced by the chain this marketplace runs now
 * (GW_VETTING_0038).
 *
 * Enabling a vetter re-runs nothing, so a snapshot still at the approval gate can carry evidence
 * the new vetter never produced. The gateway states that and does not refuse the approval — the
 * same posture as an override or a waiver — so this has to be legible without being a blocker, and
 * it has to come with the way to act on it.
 *
 * Undetermined is its own case, not folded into either answer: a run recorded before the chain
 * identity was stamped has no chain to compare, and saying "current" there would assert something
 * the gateway does not know.
 *
 * @Requirements GW_VETTING_0038
 */
export function ChainStalenessNotice({
  snapshotId,
  staleness,
  refreshable,
}: {
  snapshotId: number;
  staleness: VettingView["chainStaleness"];
  refreshable: boolean;
}) {
  const revet = useRevetSnapshot();
  if (!staleness || staleness.state === "IN_FORCE") return null;

  if (staleness.state === "UNDETERMINED") {
    return (
      <p className="text-xs text-muted-foreground">
        This run records no chain identity, so whether it matches the chain in force is unknown.
      </p>
    );
  }

  return (
    <div className="space-y-2 rounded-md border p-3">
      <p className="text-sm">
        This evidence was produced by a different chain than this marketplace runs now. Enabling or
        disabling a vetter does not re-run anything, so a vetter in the chain today may never have
        looked at this content.
      </p>
      <dl className="space-y-1 text-xs text-muted-foreground">
        <div className="flex flex-wrap gap-2">
          <dt className="font-medium">Evidence from</dt>
          <dd className="font-mono">{staleness.runChain}</dd>
        </div>
        <div className="flex flex-wrap gap-2">
          <dt className="font-medium">Chain in force</dt>
          <dd className="font-mono">{staleness.currentChain}</dd>
        </div>
      </dl>
      <p className="text-xs text-muted-foreground">
        Approval is not blocked by this. If you approve anyway, the ledger records that the
        evidence came from the superseded chain.
      </p>
      {refreshable ? (
        <Button
          size="sm"
          variant="outline"
          disabled={revet.isPending}
          aria-label={`Re-run the vetting chain on snapshot ${snapshotId}`}
          onClick={() => revet.mutate(snapshotId)}
        >
          {revet.isPending ? "Re-running the chain…" : "Re-run the chain now"}
        </Button>
      ) : null}
    </div>
  );
}

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
      suppressionKey(suppression.vetter, suppression.ruleId, suppression.location),
      suppression,
    ]),
  );
  const uncovered = vetting.data?.uncovered ?? [];
  const flow = snapshotFlow(vetting.data);
  return (
    <section aria-label={`Vetting of snapshot ${snapshotId}`} className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <ShieldCheck className="size-4 text-primary" aria-hidden />
        <span className="font-medium">Vetting</span>
        <OutcomeBadge outcome={vetting.data?.outcome} />
        {vetting.data?.recordedOutcome === "blocked" &&
        vetting.data?.outcome === "clear_with_waivers" ? (
          <span className="text-xs text-muted-foreground">
            the chain objected; active waivers are suppressing what it found
          </span>
        ) : null}
      </div>
      {/* The overview, above the detail: where this snapshot is in the chain and what stopped it.
          The per-vetter list below is unchanged — it is where findings are read side by side
          and a waiver is written next to the one being accepted. */}
      {/* Against the evidence it qualifies, above the per-vetter detail: the claim is about this
          run, so it belongs where the run is read rather than as a banner on the page. */}
      <ChainStalenessNotice
        snapshotId={snapshotId}
        staleness={vetting.data?.chainStaleness}
        refreshable={Boolean(run)}
      />
      <VettingFlow
        label={`Vetting chain of snapshot ${snapshotId}`}
        headline={snapshotHeadline(flow)}
        nodes={flow}
        suppressions={suppressions}
      />
      {uncovered.length > 0 ? (
        <div className="space-y-1 text-sm">
          <p className="text-muted-foreground">Approval is blocked until each of these is waived:</p>
          <ul aria-label="Blocking findings" className="space-y-0.5">
            {uncovered.map((finding, index) => (
              <li key={`${finding.ruleId}-${finding.location ?? ""}-${index}`} className="list-none">
                <span className="font-mono text-xs">{finding.ruleId}</span>{" "}
                <span className="text-muted-foreground">at</span>{" "}
                <span className="font-mono text-xs break-all text-muted-foreground">
                  {describeLocations(finding.locations ?? (finding.location ? [finding.location] : []))}
                </span>
              </li>
            ))}
          </ul>
        </div>
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
              key={verdict.vetter}
              snapshotId={snapshotId}
              verdict={verdict}
              suppressions={suppressions}
            />
          ))}
        </div>
      )}
      <WaiverList waivers={vetting.data?.waivers ?? []} />
      {(vetting.data?.vetters ?? []).length > 0 ? (
        <details className="text-xs text-muted-foreground">
          <summary className="cursor-pointer">What these vetters can and cannot see</summary>
          <ul className="mt-2 space-y-1">
            {(vetting.data?.vetters ?? []).map((vetter) => (
              <li key={vetter.name}>
                <span className="font-mono">{vetter.name}</span> — {vetter.description}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </section>
  );
}

import { useState, type ReactNode } from "react";
import {
  AlertTriangle,
  ChevronRight,
  CircleAlert,
  CircleCheck,
  CircleHelp,
  CircleSlash,
  PackageOpen,
  ShieldCheck,
} from "lucide-react";
import type { VettingFinding, WaiverSuppression } from "@/api/queries";
import { Timestamp } from "@/components/timestamp";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  sourceChip,
  sourceWord,
  type FlowHeadline,
  type FlowNode,
  type FlowTone,
} from "@/lib/vetting-flow";

/**
 * Tone as colour, in the theme's existing verdict language — and never on its own: every node
 * prints its state as a word beside the icon, so the flow is readable without hue.
 */
const toneText: Record<FlowTone, string> = {
  pass: "text-primary",
  warn: "text-primary",
  blocked: "text-destructive",
  idle: "text-muted-foreground",
};

/**
 * The stage's top edge, in the same two semantic colours the rest of the portal uses. It is a
 * second, pre-attentive carrier of the state the node already spells out in words — a scanner
 * finds the red stage in a long chain without reading it, and a reader who cannot rely on hue
 * loses nothing, because the word is the carrier of meaning.
 */
const toneEdge: Record<FlowTone, string> = {
  pass: "border-t-primary",
  warn: "border-t-primary",
  blocked: "border-t-destructive",
  idle: "border-t-border",
};

function NodeIcon({ node }: { node: FlowNode }) {
  const className = `size-4 ${toneText[node.tone]}`;
  if (node.kind === "step" && node.id === "ingest") return <PackageOpen className={className} aria-hidden />;
  if (node.kind === "step") return <ShieldCheck className={className} aria-hidden />;
  if (node.tone === "pass") return <CircleCheck className={className} aria-hidden />;
  if (node.tone === "warn") return <AlertTriangle className={className} aria-hidden />;
  if (node.tone === "blocked") return <CircleAlert className={className} aria-hidden />;
  if (node.state === "skipped" || node.state === "disabled")
    return <CircleSlash className={className} aria-hidden />;
  return <CircleHelp className={className} aria-hidden />;
}

/** The system's stat chip, at the size a stage node can carry two of. */
function NodeChip({ children }: { children: ReactNode }) {
  return (
    <span className="rounded-md border bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">
      {children}
    </span>
  );
}

/** Stable identity of a finding within a run, matching the report's own key. */
function findingKey(connector: string | undefined, finding: VettingFinding) {
  return `${connector ?? ""}|${finding.id ?? ""}|${finding.location ?? ""}`;
}

function FindingLine({
  finding,
  suppression,
}: {
  finding: VettingFinding;
  suppression?: WaiverSuppression;
}) {
  const waived = suppression !== undefined;
  return (
    <div className="flex flex-wrap items-baseline gap-2 text-sm">
      <Badge variant="outline">{finding.severity?.toLowerCase()}</Badge>
      <span className={`font-mono text-xs ${waived ? "line-through" : ""}`}>{finding.id}</span>
      <span className="font-mono text-xs text-muted-foreground">{finding.location ?? "—"}</span>
      <span className="text-muted-foreground">{finding.message}</span>
      {waived ? (
        <Badge variant="secondary">
          waived by {suppression.approvedBy} until <Timestamp value={suppression.expiresAt} dayOnly />
        </Badge>
      ) : null}
    </div>
  );
}

/** What one node is evidence of. The connector body is the report's row, opened from the drawing. */
function NodeDetail({
  node,
  suppressions,
}: {
  node: FlowNode;
  suppressions: Map<string, WaiverSuppression>;
}) {
  if (node.kind === "connector") {
    const findings = node.verdict?.findings ?? [];
    return (
      <div className="space-y-3">
        <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
          <dt className="font-medium">Verdict</dt>
          <dd className={toneText[node.tone]}>{node.state}</dd>
          <dt className="font-medium">Version</dt>
          <dd className="font-mono text-xs">{node.connector?.version ?? "—"}</dd>
          <dt className="font-medium">Kind</dt>
          <dd>{node.external ? "external service" : "built in"}</dd>
        </dl>
        {node.connector?.description ? (
          <p className="text-sm text-muted-foreground">{node.connector.description}</p>
        ) : null}
        {node.verdict?.detail ? (
          <p className="text-sm text-muted-foreground">{node.verdict.detail}</p>
        ) : null}
        {node.state === "skipped" ? (
          <p className="text-sm text-muted-foreground">
            An administrator switched this connector off for this marketplace, so the chain recorded
            the skip instead of running it. Who did it, at what scope and why are connector settings,
            shown on the marketplace's effective chain to an administrator — a skipped connector is
            never a clearing verdict, and a run with nothing left to clear it stays blocked.
          </p>
        ) : null}
        {findings.length > 0 ? (
          <div className="space-y-1">
            {findings.map((finding, index) => (
              <FindingLine
                key={`${finding.id}-${finding.location}-${index}`}
                finding={finding}
                suppression={suppressions.get(findingKey(node.verdict?.connector, finding))}
              />
            ))}
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">Nothing found.</p>
        )}
      </div>
    );
  }

  if (node.kind === "outcome") {
    const blocking = node.outcome?.blocking ?? [];
    const uncovered = node.outcome?.uncovered ?? [];
    return (
      <div className="space-y-3 text-sm">
        <p className="text-muted-foreground">
          The outcome is recomputed from the run and the waivers active right now: every connector
          has to clear, a skipped one counts as neither clearing nor blocking, and at least one
          connector must have cleared for the chain to clear at all.
        </p>
        {node.outcome?.recordedOutcome ? (
          <p className="text-muted-foreground">
            The connectors themselves recorded{" "}
            <span className="font-medium text-foreground">{node.outcome.recordedOutcome}</span>.
          </p>
        ) : null}
        {blocking.length > 0 ? (
          <p>
            Objecting: <span className="font-mono text-xs">{blocking.join(", ")}</span>.
          </p>
        ) : (
          <p className="text-muted-foreground">No connector is objecting.</p>
        )}
        {uncovered.length > 0 ? (
          <div className="space-y-1">
            <p>Approval is blocked until each of these is waived:</p>
            {uncovered.map((finding, index) => (
              <div
                key={`${finding.ruleId}-${finding.location}-${index}`}
                className="flex flex-wrap items-baseline gap-2"
              >
                <Badge variant="outline">{finding.severity?.toLowerCase()}</Badge>
                <span className="font-mono text-xs">{finding.ruleId}</span>
                <span className="font-mono text-xs text-muted-foreground">
                  {finding.location ?? "—"}
                </span>
                <span className="text-muted-foreground">{finding.message}</span>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-muted-foreground">No finding is waiting on a waiver.</p>
        )}
      </div>
    );
  }

  if (node.kind === "setting") {
    const setting = node.setting;
    return (
      <div className="space-y-3">
        <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
          <dt className="font-medium">State</dt>
          <dd className={toneText[node.tone]}>{node.state}</dd>
          <dt className="font-medium">Decided by</dt>
          <dd>{sourceWord(setting?.source)}</dd>
          <dt className="font-medium">Version</dt>
          <dd className="font-mono text-xs">{setting?.version ?? "—"}</dd>
          <dt className="font-medium">Kind</dt>
          <dd>{node.external ? "external service" : "built in"}</dd>
          <dt className="font-medium">Last set</dt>
          <dd>
            {setting?.updatedBy ? (
              <>
                {setting.updatedBy}, <Timestamp value={setting.updatedAt} />
              </>
            ) : (
              "—"
            )}
          </dd>
          <dt className="font-medium">Reason</dt>
          <dd>{setting?.reason ?? "—"}</dd>
        </dl>
        {setting?.description ? (
          <p className="text-sm text-muted-foreground">{setting.description}</p>
        ) : null}
      </div>
    );
  }

  return <p className="text-sm text-muted-foreground">{node.note}</p>;
}

/**
 * The vetting chain drawn as the ordered path it is: ingestion, each connector in the order it
 * runs, the aggregated outcome, and the approval gate.
 *
 * It is a list of buttons rather than a rendered graph. The chain has one predecessor and one
 * successor per node and the server already computes the order, so a layout engine would solve a
 * problem that does not exist — and a real button keeps tab order, the accessibility tree and the
 * role/name test contract working without anything having to be re-implemented.
 *
 * @Requirements GW_VETTING_0031
 */
export function VettingFlow({
  label,
  headline,
  nodes,
  suppressions,
  detailFooter,
}: {
  label: string;
  /** The one sentence above the drawing: what the chain concluded, and where it stopped. */
  headline: FlowHeadline;
  nodes: FlowNode[];
  suppressions?: Map<string, WaiverSuppression>;
  /** Extra controls for a node's detail — the marketplace chain puts its toggle here. */
  detailFooter?: (node: FlowNode) => ReactNode;
}) {
  const [openNode, setOpenNode] = useState<string | null>(null);
  const selected = nodes.find((node) => node.id === openNode);

  if (nodes.length === 0) {
    return <p className="text-sm text-muted-foreground">No chain is configured.</p>;
  }

  return (
    <>
      <p className="text-sm">
        <span className={`font-medium ${toneText[headline.tone]}`}>{headline.result}</span>
        <span className="text-muted-foreground"> · {headline.detail}</span>
      </p>
      <ol aria-label={label} className="flex flex-wrap items-stretch gap-y-2">
        {nodes.map((node, index) => {
          // The stage the chain arrives at is the one drawn as a surface rather than an outline —
          // terminal reads as terminal without a shadow, a second accent, or a size the others do
          // not have. The ring is the Card's own silhouette, borrowed for the same reason.
          const terminal = node.terminal === true;
          return (
            <li key={node.id} className="flex min-w-0 items-stretch">
              {index > 0 ? (
                <span className="flex w-6 shrink-0 items-center" aria-hidden>
                  <span className="h-px flex-1 bg-border" />
                  <ChevronRight className="-ml-1.5 size-3.5 shrink-0 text-muted-foreground/60" />
                </span>
              ) : null}
              <button
                type="button"
                aria-label={`${label}: ${node.label}, ${node.state}`}
                onClick={() => setOpenNode(node.id)}
                className={`flex min-h-20 min-w-36 flex-col gap-0.5 rounded-md border border-t-[3px] px-3 py-2 text-left outline-none transition-colors hover:bg-muted focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 motion-reduce:transition-none ${toneEdge[node.tone]} ${terminal ? "bg-muted ring-1 ring-foreground/10" : "bg-muted/40"}`}
              >
                <span className="text-[11px] font-semibold tracking-wider text-muted-foreground uppercase">
                  {node.eyebrow}
                </span>
                <span
                  className={`flex items-center gap-1.5 text-sm ${terminal ? "font-semibold" : "font-medium"}`}
                >
                  <NodeIcon node={node} />
                  {node.label}
                </span>
                <span className={`text-xs ${toneText[node.tone]}`}>{node.state}</span>
                {node.meta ? (
                  <span className="text-[11px] text-muted-foreground">{node.meta}</span>
                ) : null}
                {node.external || (node.waived ?? 0) > 0 || node.setting ? (
                  <span className="mt-auto flex flex-wrap gap-1 pt-1">
                    {node.setting ? <NodeChip>{sourceChip(node.setting.source)}</NodeChip> : null}
                    {node.external ? <NodeChip>external</NodeChip> : null}
                    {(node.waived ?? 0) > 0 ? <NodeChip>{node.waived} waived</NodeChip> : null}
                  </span>
                ) : null}
              </button>
            </li>
          );
        })}
      </ol>
      {selected ? (
        <Dialog open onOpenChange={(open) => (open ? undefined : setOpenNode(null))}>
          <DialogContent className="sm:max-w-2xl">
            <DialogHeader>
              <DialogTitle>{selected.label}</DialogTitle>
              <DialogDescription>
                {selected.eyebrow} of the chain — {selected.state}.
              </DialogDescription>
            </DialogHeader>
            <NodeDetail node={selected} suppressions={suppressions ?? new Map()} />
            {detailFooter ? detailFooter(selected) : null}
          </DialogContent>
        </Dialog>
      ) : null}
    </>
  );
}

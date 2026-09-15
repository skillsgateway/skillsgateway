/**
 * The vetting chain as an ordered path, derived from what the API already returns.
 *
 * Kept apart from the component so the rule that decides what a node says — which is the part that
 * can be wrong — is testable without a DOM, and so the snapshot flow and the marketplace flow
 * cannot drift into two different orders.
 *
 * @Requirements GW_VETTING_0031
 */
import type {
  ChainConnector,
  UncoveredFinding,
  VettingConnectorInfo,
  VettingVerdict,
  VettingView,
  WaiverSuppression,
} from "@/api/queries";

/**
 * How a node reads. Deliberately four values, mapped onto the theme's existing verdict language:
 * there is no colour here that the report did not already use.
 */
export type FlowTone = "pass" | "warn" | "blocked" | "idle";

export type FlowNodeKind = "step" | "connector" | "outcome" | "setting";

/** One node of the drawn chain. `state` is the word shown beside the icon — never colour alone. */
export interface FlowNode {
  id: string;
  kind: FlowNodeKind;
  label: string;
  state: string;
  tone: FlowTone;
  /** What this node is in the chain: `Step 2`, `Source`, `Result`, `Gate`. */
  eyebrow: string;
  /** The stage the chain arrives at, drawn as a surface so the end reads as an end. */
  terminal?: boolean;
  /** One extra fact the node itself should carry, when there is one worth the line. */
  meta?: string;
  /** One line of explanation, shown in the node's detail. */
  note?: string;
  /** A connector whose verdict the gateway delegates to an external service. */
  external?: boolean;
  /** How many of this connector's findings an active waiver is suppressing. */
  waived?: number;
  verdict?: VettingVerdict;
  connector?: VettingConnectorInfo;
  outcome?: {
    recordedOutcome?: string;
    blocking: string[];
    uncovered: UncoveredFinding[];
  };
  setting?: ChainConnector;
}

/** The verdict states, as the flow words them. `DISABLED` is not a conclusion — it is an absence. */
export function verdictWord(state: string | undefined): string {
  switch (state) {
    case "PASS":
      return "pass";
    case "WARN":
      return "warn";
    case "FAIL":
      return "fail";
    case "ERROR":
      return "error";
    case "PENDING":
      return "pending";
    case "DISABLED":
      return "skipped";
    default:
      return "not run";
  }
}

export function verdictTone(state: string | undefined): FlowTone {
  switch (state) {
    case "PASS":
      return "pass";
    case "WARN":
      return "warn";
    case "FAIL":
    case "ERROR":
      return "blocked";
    default:
      // PENDING, DISABLED and "never ran" are all absences of a conclusion. They are drawn quietly
      // and the outcome node is where the consequence is stated — a disabled connector does not
      // block, a pending one does, and inventing a shade per case would say neither.
      return "idle";
  }
}

export function outcomeWord(outcome: string | undefined): string {
  if (outcome === "CLEAR") return "clear";
  if (outcome === "CLEAR_WITH_WAIVERS") return "clear with waivers";
  return "blocked";
}

export function outcomeTone(outcome: string | undefined): FlowTone {
  if (outcome === "CLEAR") return "pass";
  if (outcome === "CLEAR_WITH_WAIVERS") return "warn";
  return "blocked";
}

/** Suppressions grouped by the connector that raised the finding, so a node can count its own. */
function waivedByConnector(suppressions: WaiverSuppression[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const suppression of suppressions) {
    const key = suppression.connector ?? "";
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return counts;
}

/**
 * The chain a snapshot actually went through: ingestion, every connector in the order the run
 * recorded, the aggregation, and the gate.
 *
 * The verdicts are the source of the order when there is a run — that is the order they executed
 * in — and the configured chain is the fallback when there is none, so a snapshot the chain never
 * ran against still draws the chain that is waiting for it rather than nothing at all.
 */
export function snapshotFlow(view: VettingView | undefined): FlowNode[] {
  const verdicts = view?.run?.verdicts ?? [];
  const configured = view?.connectors ?? [];
  const byName = new Map(configured.map((connector) => [connector.name ?? "", connector]));
  const waived = waivedByConnector(view?.suppressed ?? []);

  const nodes: FlowNode[] = [
    {
      id: "ingest",
      kind: "step",
      label: "Ingest",
      state: "quarantined",
      tone: "idle",
      eyebrow: "Source",
      note:
        "The upstream commit was cloned into quarantine and pinned by SHA. Nothing here is served;" +
        " the chain below runs against this pinned content.",
    },
  ];

  const ordered =
    verdicts.length > 0
      ? [...verdicts].sort((a, b) => (a.position ?? 0) - (b.position ?? 0))
      : configured
          .slice()
          .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
          .map((connector) => ({ connector: connector.name }) as VettingVerdict);

  ordered.forEach((verdict, index) => {
    const name = verdict.connector ?? "";
    const connector = byName.get(name);
    const findings = verdict.findings ?? [];
    nodes.push({
      id: `connector-${name}`,
      kind: "connector",
      label: name,
      state: verdictWord(verdict.state),
      tone: verdictTone(verdict.state),
      eyebrow: `Step ${index + 1}`,
      meta: findings.length > 0 ? `${findings.length} ${plural(findings.length, "finding")}` : undefined,
      external: connector?.external === true,
      waived: waived.get(name) ?? 0,
      verdict,
      connector,
    });
  });

  const blocking = ordered
    .filter((verdict) => verdict.state === "FAIL" || verdict.state === "ERROR" || verdict.state === "PENDING")
    .map((verdict) => verdict.connector ?? "");

  nodes.push({
    id: "outcome",
    kind: "outcome",
    label: "Outcome",
    state: outcomeWord(view?.outcome),
    tone: outcomeTone(view?.outcome),
    eyebrow: "Result",
    // The aggregation is what the whole chain is for, so it is the stage drawn as arrival.
    terminal: true,
    outcome: {
      recordedOutcome: view?.recordedOutcome,
      blocking,
      uncovered: view?.uncovered ?? [],
    },
  });

  const blocked = (view?.outcome ?? "BLOCKED") === "BLOCKED";
  nodes.push({
    id: "gate",
    kind: "step",
    label: "Approval",
    state: blocked ? "closed" : "open",
    tone: blocked ? "blocked" : "idle",
    eyebrow: "Gate",
    note: blocked
      ? "Approval is refused while the effective outcome is blocked. Each blocking finding has to be" +
        " accepted with a justified, expiring waiver before the gate opens."
      : "The chain no longer objects. A person still has to approve: approval is what publishes the" +
        " snapshot to the git facade, and nothing is served until it happens.",
  });

  return nodes;
}

/**
 * The chain as configured for one marketplace, with no snapshot in front of it: what will run, and
 * which setting decided that.
 *
 * @Requirements GW_VETTING_0029.5
 */
export function marketplaceFlow(chain: ChainConnector[]): FlowNode[] {
  const nodes: FlowNode[] = [
    {
      id: "ingest",
      kind: "step",
      label: "Ingest",
      state: "quarantined",
      tone: "idle",
      eyebrow: "Source",
      note: "Every snapshot of this marketplace enters quarantine and runs the chain below.",
    },
  ];

  chain.forEach((connector, index) => {
    const name = connector.name ?? "";
    nodes.push({
      id: `connector-${name}`,
      kind: "setting",
      label: name,
      state: connector.enabled ? "enabled" : "disabled",
      tone: connector.enabled ? "pass" : "idle",
      eyebrow: `Step ${index + 1}`,
      // Who switched it off and when, on the node itself: a disabled step is the one an
      // administrator scanning the chain has to be able to account for without opening it.
      meta:
        !connector.enabled && connector.updatedBy
          ? `off by ${connector.updatedBy}`
          : undefined,
      external: connector.external === true,
      setting: connector,
    });
  });

  nodes.push({
    id: "gate",
    kind: "step",
    label: "Approval",
    state: "held",
    tone: "idle",
    eyebrow: "Gate",
    // No verdicts here, so the gate is where this drawing arrives.
    terminal: true,
    note:
      "However the chain is configured, every snapshot is held until a person approves it." +
      " Switching connectors off narrows the evidence behind that decision; it never makes it" +
      " automatic — a run with nothing left to clear it is blocked, not clear.",
  });

  return nodes;
}

/** Where a connector's effective state came from, as an administrator reads it. */
export function sourceWord(source: string | undefined): string {
  if (source === "MARKETPLACE") return "set for this marketplace";
  if (source === "GLOBAL") return "from the global setting";
  return "default — no setting recorded";
}

/** The same, short enough to sit on the node as a chip. */
export function sourceChip(source: string | undefined): string {
  if (source === "MARKETPLACE") return "this marketplace";
  if (source === "GLOBAL") return "global";
  return "default";
}

function plural(count: number, word: string): string {
  return count === 1 ? word : `${word}s`;
}

/**
 * The one sentence above the drawing: what the chain concluded and where it stopped.
 *
 * A reader should not have to parse five nodes to learn the answer — the nodes are where they go
 * *next*, to find out why. Derived from the same node list the drawing renders, so the sentence
 * and the picture cannot disagree.
 */
export interface FlowHeadline {
  /** The answer, in one or two words. */
  result: string;
  tone: FlowTone;
  /** What stands behind it: where it stopped, or what it is made of. */
  detail: string;
}

/** The headline for a snapshot's chain. */
export function snapshotHeadline(nodes: FlowNode[]): FlowHeadline {
  const connectors = nodes.filter((node) => node.kind === "connector");
  const outcome = nodes.find((node) => node.kind === "outcome");
  const ran = connectors.filter((node) => node.state !== "not run");
  const findings = connectors.reduce((total, node) => total + (node.verdict?.findings ?? []).length, 0);
  const waived = connectors.reduce((total, node) => total + (node.waived ?? 0), 0);

  if (outcome?.state === "clear") {
    return {
      result: "Clear",
      tone: "pass",
      detail: `${connectors.length} ${plural(connectors.length, "connector")}, ${findings} ${plural(findings, "finding")}`,
    };
  }
  if (outcome?.state === "clear with waivers") {
    return {
      result: "Clear with waivers",
      tone: "warn",
      detail: `${waived} ${plural(waived, "finding")} accepted`,
    };
  }
  if (ran.length === 0) {
    return { result: "Blocked", tone: "blocked", detail: "the chain has not run against this snapshot" };
  }

  const stoppedAt = connectors.findIndex((node) => node.tone === "blocked" || node.state === "pending");
  if (stoppedAt < 0) {
    return { result: "Blocked", tone: "blocked", detail: "no connector cleared this snapshot" };
  }
  const node = connectors[stoppedAt]!;
  return {
    result: `Blocked at step ${stoppedAt + 1}`,
    tone: "blocked",
    detail: `${node.label} ${stoppedReason(node)}`,
  };
}

/** Why the chain stopped at this node, in the words its own verdict supports. */
function stoppedReason(node: FlowNode): string {
  if (node.state === "pending") return "has not answered yet";
  if (node.state === "error") return "did not produce a verdict";
  const findings = node.verdict?.findings ?? [];
  if (findings.length === 0) return "objected";
  const worst = worstSeverity(findings.map((finding) => finding.severity));
  return `found ${findings.length} ${worst ? `${worst.toLowerCase()} ` : ""}${plural(findings.length, "finding")}`;
}

/** Severity order as the gateway ranks it; the worst present is the one worth naming. */
const SEVERITY_ORDER = ["INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"];

function worstSeverity(severities: (string | undefined)[]): string | undefined {
  let worst: string | undefined;
  for (const severity of severities) {
    if (severity === undefined) continue;
    if (worst === undefined || SEVERITY_ORDER.indexOf(severity) > SEVERITY_ORDER.indexOf(worst)) {
      worst = severity;
    }
  }
  return worst;
}

/**
 * The headline for a marketplace's configured chain: how much of it runs, and what is off.
 *
 * @Requirements GW_VETTING_0029.5
 */
export function marketplaceHeadline(nodes: FlowNode[]): FlowHeadline {
  const connectors = nodes.filter((node) => node.kind === "setting");
  const off = connectors.filter((node) => node.setting?.enabled !== true);
  const on = connectors.length - off.length;
  return {
    result: `${on} of ${connectors.length} ${plural(connectors.length, "connector")} run`,
    // Never "good": a narrowed chain is a fact for an administrator to weigh, not a pass.
    tone: off.length === 0 ? "pass" : "idle",
    detail:
      off.length === 0
        ? "every configured connector runs for this marketplace"
        : off
            .map(
              (node) =>
                `${node.label} off ${node.setting?.source === "GLOBAL" ? "globally" : "for this marketplace"}`,
            )
            .join(", "),
  };
}

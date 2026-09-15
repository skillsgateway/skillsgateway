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

  for (const verdict of ordered) {
    const name = verdict.connector ?? "";
    const connector = byName.get(name);
    nodes.push({
      id: `connector-${name}`,
      kind: "connector",
      label: name,
      state: verdictWord(verdict.state),
      tone: verdictTone(verdict.state),
      external: connector?.external === true,
      waived: waived.get(name) ?? 0,
      verdict,
      connector,
    });
  }

  const blocking = ordered
    .filter((verdict) => verdict.state === "FAIL" || verdict.state === "ERROR" || verdict.state === "PENDING")
    .map((verdict) => verdict.connector ?? "");

  nodes.push({
    id: "outcome",
    kind: "outcome",
    label: "Outcome",
    state: outcomeWord(view?.outcome),
    tone: outcomeTone(view?.outcome),
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
      note: "Every snapshot of this marketplace enters quarantine and runs the chain below.",
    },
  ];

  for (const connector of chain) {
    const name = connector.name ?? "";
    nodes.push({
      id: `connector-${name}`,
      kind: "setting",
      label: name,
      state: connector.enabled ? "enabled" : "disabled",
      tone: connector.enabled ? "pass" : "idle",
      external: connector.external === true,
      setting: connector,
    });
  }

  nodes.push({
    id: "gate",
    kind: "step",
    label: "Approval",
    state: "held",
    tone: "idle",
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

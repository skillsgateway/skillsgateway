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
  ChainVetter,
  UncoveredFinding,
  VetterInfo,
  VettingFinding,
  VettingVerdict,
  VettingView,
  WaiverSuppression,
} from "@/api/queries";

/**
 * How a node reads. Deliberately four values, mapped onto the theme's existing verdict language:
 * there is no colour here that the report did not already use.
 */
export type FlowTone = "pass" | "warn" | "blocked" | "idle";

export type FlowNodeKind = "step" | "vetter" | "outcome" | "setting";

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
  /** A vetter whose verdict the gateway delegates to an external service. */
  external?: boolean;
  /** How many of this vetter's findings an active waiver is suppressing. */
  waived?: number;
  verdict?: VettingVerdict;
  vetter?: VetterInfo;
  outcome?: {
    recordedOutcome?: string;
    blocking: string[];
    uncovered: UncoveredFinding[];
    /** Vetters the run never got to, because the chain stopped. Empty for a complete run. */
    notReached: string[];
  };
  setting?: ChainVetter;
}

/**
 * The verdict states, as the flow words them. `DISABLED` and `NOT_REACHED` are not conclusions —
 * they are absences, and different ones: an administrator switched that vetter off, or the chain
 * stopped before it got there. Both are told apart from "no verdict at all".
 */
export function verdictWord(state: string | undefined): string {
  switch (state) {
    case "pass":
      return "pass";
    case "warn":
      return "warn";
    case "fail":
      return "fail";
    case "error":
      return "error";
    case "pending":
      return "pending";
    case "disabled":
      return "skipped";
    case "not_reached":
      return "not reached";
    default:
      return "not run";
  }
}

export function verdictTone(state: string | undefined): FlowTone {
  switch (state) {
    case "pass":
      return "pass";
    case "warn":
      return "warn";
    case "fail":
    case "error":
      return "blocked";
    default:
      // PENDING, DISABLED, NOT_REACHED and "never ran" are all absences of a conclusion. They are
      // drawn quietly and the outcome node is where the consequence is stated — a disabled vetter
      // does not block, a pending one does, a not-reached one blocks the whole run until it is
      // re-run — and inventing a shade per case would say none of it.
      return "idle";
  }
}

export function outcomeWord(outcome: string | undefined): string {
  if (outcome === "clear") return "clear";
  if (outcome === "clear_with_waivers") return "clear with waivers";
  return "blocked";
}

export function outcomeTone(outcome: string | undefined): FlowTone {
  if (outcome === "clear") return "pass";
  if (outcome === "clear_with_waivers") return "warn";
  return "blocked";
}

/** Suppressions grouped by the vetter that raised the finding, so a node can count its own. */
function waivedByVetter(suppressions: WaiverSuppression[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const suppression of suppressions) {
    const key = suppression.vetter ?? "";
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return counts;
}

/**
 * The chain a snapshot actually went through: ingestion, every vetter in the order the run
 * recorded, the aggregation, and the gate.
 *
 * The verdicts are the source of the order when there is a run — that is the order they executed
 * in — and the configured chain is the fallback when there is none, so a snapshot the chain never
 * ran against still draws the chain that is waiting for it rather than nothing at all.
 */
export function snapshotFlow(view: VettingView | undefined): FlowNode[] {
  const verdicts = view?.run?.verdicts ?? [];
  const configured = view?.vetters ?? [];
  const byName = new Map(configured.map((vetter) => [vetter.name ?? "", vetter]));
  const waived = waivedByVetter(view?.suppressed ?? []);

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
          .map((vetter) => ({ vetter: vetter.name }) as VettingVerdict);

  ordered.forEach((verdict, index) => {
    const name = verdict.vetter ?? "";
    const vetter = byName.get(name);
    const findings = entriesOf(verdict);
    nodes.push({
      id: `vetter-${name}`,
      kind: "vetter",
      label: name,
      state: verdictWord(verdict.state),
      tone: verdictTone(verdict.state),
      eyebrow: `Step ${index + 1}`,
      // The bookkeeping finding a not-reached or disabled verdict carries is a record of why the
      // vetter did not run, not something it found. Counting it on the node would read as a
      // result, which is the one thing these states must never look like.
      meta:
        findings.length > 0 && verdict.state !== "not_reached" && verdict.state !== "disabled"
          ? `${findings.length} ${plural(findings.length, "finding")}`
          : undefined,
      external: vetter?.external === true,
      waived: waived.get(name) ?? 0,
      verdict,
      vetter,
    });
  });

  const blocking = ordered
    .filter((verdict) => verdict.state === "fail" || verdict.state === "error" || verdict.state === "pending")
    .map((verdict) => verdict.vetter ?? "");

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
      notReached: ordered
        .filter((verdict) => verdict.state === "not_reached")
        .map((verdict) => verdict.vetter ?? ""),
    },
  });

  const blocked = (view?.outcome ?? "blocked") === "blocked";
  nodes.push({
    id: "gate",
    kind: "step",
    label: "Approval",
    state: blocked ? "closed" : "open",
    tone: blocked ? "blocked" : "idle",
    eyebrow: "Gate",
    note: blocked
      ? "Approval is refused while the effective outcome is blocked. Each blocking finding has to be" +
        " accepted with a justified, expiring waiver before the gate opens. Where the chain stopped" +
        " early, a waiver alone will not open it: the run has to be repeated so that the vetters" +
        " which never looked get to."
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
export function marketplaceFlow(chain: ChainVetter[]): FlowNode[] {
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

  chain.forEach((vetter, index) => {
    const name = vetter.name ?? "";
    nodes.push({
      id: `vetter-${name}`,
      kind: "setting",
      label: name,
      state: vetter.enabled ? "enabled" : "disabled",
      tone: vetter.enabled ? "pass" : "idle",
      eyebrow: `Step ${index + 1}`,
      // Who switched it off and when, on the node itself: a disabled step is the one an
      // administrator scanning the chain has to be able to account for without opening it.
      meta:
        !vetter.enabled && vetter.updatedBy
          ? `off by ${vetter.updatedBy}`
          : undefined,
      external: vetter.external === true,
      setting: vetter,
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
      " Switching vetters off narrows the evidence behind that decision; it never makes it" +
      " automatic — a run with nothing left to clear it is blocked, not clear.",
  });

  return nodes;
}

/** Where a vetter's effective state came from, as an administrator reads it. */
export function sourceWord(source: string | undefined): string {
  if (source === "marketplace") return "set for this marketplace";
  if (source === "global") return "from the global setting";
  return "default — no setting recorded";
}

/** The same, short enough to sit on the node as a chip. */
export function sourceChip(source: string | undefined): string {
  if (source === "marketplace") return "this marketplace";
  if (source === "global") return "global";
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

/**
 * What a verdict counts as findings: its groups — identical content at several locations is one
 * thing to judge (GW_VETTING_0041) — or its findings, for a view that carries no groups.
 */
function entriesOf(verdict: VettingVerdict | undefined): { severity?: string }[] {
  return verdict?.groups ?? verdict?.findings ?? [];
}

/** The headline for a snapshot's chain. */
export function snapshotHeadline(nodes: FlowNode[]): FlowHeadline {
  const vetters = nodes.filter((node) => node.kind === "vetter");
  const outcome = nodes.find((node) => node.kind === "outcome");
  const ran = vetters.filter((node) => node.state !== "not run" && node.state !== "not reached");
  const findings = vetters.reduce((total, node) => total + entriesOf(node.verdict).length, 0);
  const waived = vetters.reduce((total, node) => total + (node.waived ?? 0), 0);

  if (outcome?.state === "clear") {
    return {
      result: "Clear",
      tone: "pass",
      detail: `${vetters.length} ${plural(vetters.length, "vetter")}, ${findings} ${plural(findings, "finding")}`,
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

  const stoppedAt = vetters.findIndex((node) => node.tone === "blocked" || node.state === "pending");
  if (stoppedAt < 0) {
    return { result: "Blocked", tone: "blocked", detail: "no vetter cleared this snapshot" };
  }
  const node = vetters[stoppedAt]!;

  // A chain that stopped early is the one case where the count of vetters is not the count of
  // opinions, so the headline says so rather than leaving a reader to infer it from the nodes: a
  // shorter chain must never read as a cleaner one.
  const notReached = vetters.filter((candidate) => candidate.state === "not reached").length;
  if (notReached > 0) {
    return {
      result: `Stopped at step ${stoppedAt + 1}`,
      tone: "blocked",
      detail:
        `${node.label} ${stoppedReason(node)}, so ${notReached} later ` +
        `${plural(notReached, "vetter")} did not run — re-vet to see what they say`,
    };
  }

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
  const findings = entriesOf(node.verdict);
  if (findings.length === 0) return "objected";
  const worst = worstSeverity(findings.map((finding) => finding.severity));
  return `found ${findings.length} ${worst ? `${worst.toLowerCase()} ` : ""}${plural(findings.length, "finding")}`;
}

/** Severity order as the gateway ranks it; the worst present is the one worth naming. */
const SEVERITY_ORDER = ["info", "low", "medium", "high", "critical"];

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
export function marketplaceHeadline(
  nodes: FlowNode[],
  mode?: string,
  // The same drawing serves one marketplace and the estate's default, and the difference is
  // entirely in what "here" means. Saying "for this marketplace" on the page that governs every
  // marketplace with no override would name the wrong scope on the page's own headline.
  scope: "marketplace" | "default" = "marketplace",
): FlowHeadline {
  const here = scope === "marketplace" ? "for this marketplace" : "by default";
  const vetters = nodes.filter((node) => node.kind === "setting");
  const off = vetters.filter((node) => node.setting?.enabled !== true);
  const on = vetters.length - off.length;
  const stops = mode === "stop-after-fail";
  const what =
    off.length === 0
      ? `every configured vetter runs ${here}`
      : off
          .map((node) => `${node.label} off ${node.setting?.source === "global" ? "globally" : here}`)
          .join(", ");
  return {
    result: `${on} of ${vetters.length} ${plural(vetters.length, "vetter")} run`,
    // Never "good": a narrowed chain is a fact for an administrator to weigh, not a pass. A chain
    // that stops early is narrower still, so it cannot read as a pass either.
    tone: off.length === 0 && !stops ? "pass" : "idle",
    detail: stops ? `${what} — and the chain stops at the first failure` : what,
  };
}

/** How many locations a list spells out before counting the rest. */
const SHOWN_LOCATIONS = 3;

/** `a:1, b:1, c:1 and 4 more` — every location a reviewer is being asked about, never a bare rule. */
export function describeLocations(locations: readonly string[]) {
  if (locations.length === 0) return "—";
  const shown = locations.slice(0, SHOWN_LOCATIONS).join(", ");
  const more = locations.length - SHOWN_LOCATIONS;
  return more > 0 ? `${shown} and ${more} more` : shown;
}

/** What a waiver form accepts: one finding, or one group of findings on identical content. */
export interface WaiveTarget {
  ruleId: string;
  /** Every `path:line` the target stands for; one entry for a single finding. */
  locations: string[];
  /** The group's git blob id. Present only when the gateway tied the finding to content. */
  content?: string;
  line?: number;
}

/** A single finding as a waiver target — for a finding that did not come from a group. */
export function targetOf(finding: VettingFinding): WaiveTarget {
  return {
    ruleId: finding.id ?? "",
    locations: finding.location ? [finding.location] : [],
    content: finding.content,
    line: finding.location ? Number(/:(\d+)$/.exec(finding.location)?.[1]) || undefined : undefined,
  };
}

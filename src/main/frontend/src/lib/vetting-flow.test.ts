import { describe, expect, it, test } from "vitest";
import {
  blockedVetting,
  clearVetting,
  disabledAndPendingVetting,
  marketplaceChain,
  waivedVetting,
} from "@/test/msw-handlers";
import type { VettingView } from "@/api/queries";
import {
  effectiveState,
  findingCount,
  marketplaceFlow,
  marketplaceHeadline,
  snapshotFlow,
  snapshotHeadline,
  sourceChip,
  sourceWord,
} from "./vetting-flow";

describe("snapshotFlow", () => {
  it("draws ingest, every vetter in run order, the outcome and the gate", () => {
    const nodes = snapshotFlow(blockedVetting);
    expect(nodes.map((node) => node.id)).toEqual([
      "ingest",
      "vetter-secret-scan",
      "vetter-prompt-injection",
      "outcome",
      "gate",
    ]);
  });

  it("orders the vetters by the position the run recorded, not by the array order", () => {
    const shuffled = {
      ...blockedVetting,
      run: {
        ...blockedVetting.run!,
        verdicts: [...blockedVetting.run!.verdicts!].reverse(),
      },
    };
    expect(snapshotFlow(shuffled).map((node) => node.label)).toEqual([
      "Ingest",
      "secret-scan",
      "prompt-injection",
      "Outcome",
      "Approval",
    ]);
  });

  it("states every verdict as a word, never as colour alone", () => {
    const nodes = snapshotFlow(blockedVetting);
    expect(nodes.find((node) => node.id === "vetter-secret-scan")?.state).toBe("fail");
    expect(nodes.find((node) => node.id === "vetter-prompt-injection")?.state).toBe("pass");
    expect(nodes.find((node) => node.id === "outcome")?.state).toBe("blocked");
  });

  it("closes the gate while the effective outcome is blocked and opens it when it clears", () => {
    expect(snapshotFlow(blockedVetting).find((node) => node.id === "gate")?.state).toBe("closed");
    expect(snapshotFlow(clearVetting).find((node) => node.id === "gate")?.state).toBe("open");
  });

  it("reads a vetter whose blocking findings are all waived as waived, and counts the acceptance", () => {
    const node = snapshotFlow(waivedVetting).find((n) => n.id === "vetter-secret-scan");
    // Accepted risk is never rendered as a clean result: "waived", in the outcome's tone, not "pass".
    expect(node?.state).toBe("waived");
    expect(node?.tone).toBe("warn");
    expect(node?.waived).toBe(1);
    expect(snapshotFlow(waivedVetting).find((n) => n.id === "outcome")?.state).toBe(
      "clear with waivers",
    );
  });

  it("words a skipped and a pending vetter as absences, and marks the external one", () => {
    const nodes = snapshotFlow(disabledAndPendingVetting);
    const skipped = nodes.find((node) => node.id === "vetter-secret-scan");
    const pending = nodes.find((node) => node.id === "vetter-corp-llm-reviewer");
    expect(skipped?.state).toBe("skipped");
    expect(skipped?.tone).toBe("idle");
    expect(pending?.state).toBe("pending");
    expect(pending?.external).toBe(true);
    // A pending vetter has not answered, so the outcome node names it as objecting.
    expect(nodes.find((node) => node.id === "outcome")?.outcome?.blocking).toContain(
      "corp-llm-reviewer",
    );
  });

  it("draws the configured chain waiting for a snapshot the chain never ran against", () => {
    const nodes = snapshotFlow({ ...blockedVetting, run: undefined });
    expect(nodes.map((node) => node.label)).toEqual([
      "Ingest",
      "secret-scan",
      "prompt-injection",
      "Outcome",
      "Approval",
    ]);
    expect(nodes[1]?.state).toBe("not run");
    expect(nodes.find((node) => node.id === "outcome")?.state).toBe("blocked");
  });

  it("is blocked, never unknown, when there is nothing at all", () => {
    const nodes = snapshotFlow(undefined);
    expect(nodes.map((node) => node.id)).toEqual(["ingest", "outcome", "gate"]);
    expect(nodes[1]?.state).toBe("blocked");
    expect(nodes[2]?.state).toBe("closed");
  });
});

describe("marketplaceFlow", () => {
  it("draws every configured vetter with its enabled state, in chain order", () => {
    const nodes = marketplaceFlow(marketplaceChain);
    expect(nodes.map((node) => node.label)).toEqual([
      "Ingest",
      "secret-scan",
      "prompt-injection",
      "license-scan",
      "Approval",
    ]);
    expect(nodes[1]?.state).toBe("disabled");
    expect(nodes[2]?.state).toBe("enabled");
  });

  it("keeps the deciding setting on the node so the detail can name it", () => {
    const nodes = marketplaceFlow(marketplaceChain);
    expect(nodes[1]?.setting?.source).toBe("marketplace");
    expect(nodes[2]?.setting?.source).toBe("global");
    expect(nodes[3]?.setting?.source).toBe("default");
  });
});

describe("snapshotHeadline", () => {
  it("names the step the chain stopped at and the worst severity behind it", () => {
    const headline = snapshotHeadline(snapshotFlow(blockedVetting));
    expect(headline.result).toBe("Blocked at step 1");
    expect(headline.detail).toBe("secret-scan found 1 critical finding");
    expect(headline.tone).toBe("blocked");
  });

  it("counts the chain rather than naming a step when it cleared", () => {
    const headline = snapshotHeadline(snapshotFlow(clearVetting));
    expect(headline.result).toBe("Clear");
    expect(headline.detail).toBe("2 vetters, 0 findings");
    expect(headline.tone).toBe("pass");
  });

  it("says what was accepted when it only cleared because of a waiver", () => {
    const headline = snapshotHeadline(snapshotFlow(waivedVetting));
    expect(headline.result).toBe("Clear with waivers");
    expect(headline.detail).toBe("1 finding accepted");
    // Never the pass tone: a snapshot that cleared on an acceptance is not a clean one.
    expect(headline.tone).toBe("warn");
  });

  it("skips past a disabled step to the one that is actually holding the chain", () => {
    const headline = snapshotHeadline(snapshotFlow(disabledAndPendingVetting));
    expect(headline.result).toBe("Blocked at step 3");
    expect(headline.detail).toBe("corp-llm-reviewer has not answered yet");
  });

  it("says the chain has not run rather than naming a step that never happened", () => {
    const headline = snapshotHeadline(snapshotFlow({ ...blockedVetting, run: undefined }));
    expect(headline.result).toBe("Blocked");
    expect(headline.detail).toBe("the chain has not run against this snapshot");
  });
});

describe("marketplaceHeadline", () => {
  it("counts what runs and names what is off, with the scope it is off at", () => {
    const headline = marketplaceHeadline(marketplaceFlow(marketplaceChain));
    expect(headline.result).toBe("2 of 3 vetters run");
    expect(headline.detail).toBe("secret-scan off for this marketplace");
    // A narrowed chain is a fact to weigh, not a pass.
    expect(headline.tone).toBe("idle");
  });

  it("reads as a pass only when the whole configured chain runs", () => {
    const all = marketplaceChain.map((vetter) => ({ ...vetter, enabled: true }));
    const headline = marketplaceHeadline(marketplaceFlow(all));
    expect(headline.result).toBe("3 of 3 vetters run");
    expect(headline.detail).toBe("every configured vetter runs for this marketplace");
    expect(headline.tone).toBe("pass");
  });

  it("names a globally disabled vetter as off globally", () => {
    const globallyOff = marketplaceChain.map((vetter) =>
      vetter.name === "prompt-injection"
        ? { ...vetter, enabled: false, source: "global" as const }
        : { ...vetter, enabled: true },
    );
    expect(marketplaceHeadline(marketplaceFlow(globallyOff)).detail).toBe(
      "prompt-injection off globally",
    );
  });
});

describe("stage identity", () => {
  it("numbers the vetter stages and names the ends of the chain", () => {
    expect(snapshotFlow(blockedVetting).map((node) => node.eyebrow)).toEqual([
      "Source",
      "Step 1",
      "Step 2",
      "Result",
      "Gate",
    ]);
  });

  it("puts who switched a vetter off on the node itself", () => {
    const nodes = marketplaceFlow(marketplaceChain);
    expect(nodes[1]?.meta).toBe("off by alice");
    expect(nodes[2]?.meta).toBeUndefined();
  });
});

describe("sourceChip", () => {
  it("names the scope short enough to sit on a node", () => {
    expect(sourceChip("marketplace")).toBe("this marketplace");
    expect(sourceChip("global")).toBe("global");
    expect(sourceChip(undefined)).toBe("default");
  });
});

describe("sourceWord", () => {
  it("names the absence of a setting as a default rather than as a decision", () => {
    expect(sourceWord("marketplace")).toBe("set for this marketplace");
    expect(sourceWord("global")).toBe("from the global setting");
    expect(sourceWord("default")).toBe("default — no setting recorded");
    expect(sourceWord(undefined)).toBe("default — no setting recorded");
  });
});

const LONG = "plugins/x/skills/y/SKILL.md";

/** One license-scan verdict of 18 findings in 3 groups: 16 copies of one file, and two of their own. */
const groupedVetting: VettingView = {
  ...blockedVetting,
  outcome: "blocked",
  run: {
    ...blockedVetting.run!,
    verdicts: [
      {
        vetter: "license-scan",
        position: 0,
        state: "fail",
        detail: "18 finding(s); worst high",
        groups: [
          {
            ruleId: "no-license",
            severity: "high",
            message: "no licence",
            locations: Array.from({ length: 16 }, (_, i) => `plugins/p${i}/LICENSE:1`),
          },
          { ruleId: "copyleft", severity: "medium", message: "copyleft", locations: [`${LONG}:3`] },
          { ruleId: "copyleft", severity: "medium", message: "copyleft", locations: [`${LONG}:9`] },
        ],
      },
      {
        vetter: "secret-scan",
        position: 1,
        state: "warn",
        groups: [
          { ruleId: "generic-token", severity: "medium", message: "a token", locations: ["a.md:1"] },
          { ruleId: "generic-token", severity: "medium", message: "a token", locations: ["b.md:1"] },
        ],
      },
    ],
  },
};

/**
 * @SVCs SVC_GW_VETTING_0074
 */
test("the_step_headline_and_stop_reason_count_findings_and_groups_in_one_wording", () => {
  const nodes = snapshotFlow(groupedVetting);
  expect(nodes.find((node) => node.id === "vetter-license-scan")?.meta).toBe("18 findings in 3 groups");
  // Nothing collapsed: the groups are not named.
  expect(nodes.find((node) => node.id === "vetter-secret-scan")?.meta).toBe("2 findings");
  // 16 of the 18 are high: the worst severity is named, not stretched over every finding.
  expect(snapshotHeadline(nodes).detail).toBe("license-scan found 18 findings in 3 groups, worst high");

  const clear = snapshotFlow({ ...groupedVetting, outcome: "clear" });
  expect(snapshotHeadline(clear).detail).toBe("2 vetters, 20 findings in 5 groups");

  expect(findingCount({ findings: 1, groups: 1 })).toBe("1 finding");
  expect(findingCount({ findings: 4, groups: 1 }, "high")).toBe("4 high findings in 1 group");
});

/**
 * @SVCs SVC_GW_VETTING_0076
 */
test("a_failed_vetter_with_every_blocking_finding_waived_is_waived_and_not_objecting", () => {
  const suppress = (location: string) => ({
    vetter: "license-scan",
    ruleId: "no-license",
    location,
    approvedBy: "alice",
    expiresAt: "2099-01-01T00:00:00Z",
  });
  const all = Array.from({ length: 16 }, (_, i) => suppress(`plugins/p${i}/LICENSE:1`));
  const waived = snapshotFlow({ ...groupedVetting, outcome: "clear_with_waivers", suppressed: all });
  const node = waived.find((n) => n.id === "vetter-license-scan");
  // The medium findings left over do not block, so the vetter clears on the waivers alone.
  expect(node?.state).toBe("waived");
  expect(node?.tone).toBe("warn");
  expect(node?.waived).toBe(16);
  expect(waived.find((n) => n.id === "outcome")?.outcome?.blocking).toEqual([]);

  // One blocking finding left: still a failure, still objecting.
  const partial = snapshotFlow({ ...groupedVetting, suppressed: all.slice(1) });
  expect(partial.find((n) => n.id === "vetter-license-scan")?.state).toBe("fail");
  expect(partial.find((n) => n.id === "outcome")?.outcome?.blocking).toEqual(["license-scan"]);

  // A waiver on another vetter's finding does not count.
  const elsewhere = all.map((s) => ({ ...s, vetter: "secret-scan" }));
  expect(effectiveState(groupedVetting.run!.verdicts![0], elsewhere)).toBe("fail");

  // Never for a vetter that did not run.
  const notReached = { vetter: "x", state: "not_reached" as const, findings: [{ id: "vetter-not-reached", severity: "info" as const, location: "x" }] };
  expect(effectiveState(notReached, [{ vetter: "x", ruleId: "vetter-not-reached", location: "x" }])).toBe("not_reached");
  const disabled = { ...notReached, state: "disabled" as const };
  expect(effectiveState(disabled, [{ vetter: "x", ruleId: "vetter-not-reached", location: "x" }])).toBe("disabled");
  // Nothing waived: the recorded state stands.
  expect(effectiveState({ vetter: "x", state: "error" }, [])).toBe("error");
});

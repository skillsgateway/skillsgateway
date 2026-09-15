import { describe, expect, it } from "vitest";
import {
  blockedVetting,
  clearVetting,
  disabledAndPendingVetting,
  marketplaceChain,
  waivedVetting,
} from "@/test/msw-handlers";
import { marketplaceFlow, snapshotFlow, sourceWord } from "./vetting-flow";

describe("snapshotFlow", () => {
  it("draws ingest, every connector in run order, the outcome and the gate", () => {
    const nodes = snapshotFlow(blockedVetting);
    expect(nodes.map((node) => node.id)).toEqual([
      "ingest",
      "connector-secret-scan",
      "connector-prompt-injection",
      "outcome",
      "gate",
    ]);
  });

  it("orders the connectors by the position the run recorded, not by the array order", () => {
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
    expect(nodes.find((node) => node.id === "connector-secret-scan")?.state).toBe("fail");
    expect(nodes.find((node) => node.id === "connector-prompt-injection")?.state).toBe("pass");
    expect(nodes.find((node) => node.id === "outcome")?.state).toBe("blocked");
  });

  it("closes the gate while the effective outcome is blocked and opens it when it clears", () => {
    expect(snapshotFlow(blockedVetting).find((node) => node.id === "gate")?.state).toBe("closed");
    expect(snapshotFlow(clearVetting).find((node) => node.id === "gate")?.state).toBe("open");
  });

  it("keeps a waived finding's connector at the verdict it reached, and counts the acceptance", () => {
    const node = snapshotFlow(waivedVetting).find((n) => n.id === "connector-secret-scan");
    // Accepted risk is never rendered as a clean result: the connector still failed.
    expect(node?.state).toBe("fail");
    expect(node?.waived).toBe(1);
    expect(snapshotFlow(waivedVetting).find((n) => n.id === "outcome")?.state).toBe(
      "clear with waivers",
    );
  });

  it("words a skipped and a pending connector as absences, and marks the external one", () => {
    const nodes = snapshotFlow(disabledAndPendingVetting);
    const skipped = nodes.find((node) => node.id === "connector-secret-scan");
    const pending = nodes.find((node) => node.id === "connector-corp-llm-reviewer");
    expect(skipped?.state).toBe("skipped");
    expect(skipped?.tone).toBe("idle");
    expect(pending?.state).toBe("pending");
    expect(pending?.external).toBe(true);
    // A pending connector has not answered, so the outcome node names it as objecting.
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
  it("draws every configured connector with its enabled state, in chain order", () => {
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
    expect(nodes[1]?.setting?.source).toBe("MARKETPLACE");
    expect(nodes[2]?.setting?.source).toBe("GLOBAL");
    expect(nodes[3]?.setting?.source).toBe("DEFAULT");
  });
});

describe("sourceWord", () => {
  it("names the absence of a setting as a default rather than as a decision", () => {
    expect(sourceWord("MARKETPLACE")).toBe("set for this marketplace");
    expect(sourceWord("GLOBAL")).toBe("from the global setting");
    expect(sourceWord("DEFAULT")).toBe("default — no setting recorded");
    expect(sourceWord(undefined)).toBe("default — no setting recorded");
  });
});

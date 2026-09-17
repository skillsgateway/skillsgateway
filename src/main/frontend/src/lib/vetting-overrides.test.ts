import { describe, expect, test } from "vitest";
import { chainOverrides, vetterToggles } from "@/test/msw-handlers";
import type { MarketplaceView } from "@/api/queries";
import { overridesOf, plannedChanges } from "./vetting-overrides";

const MARKETPLACES: MarketplaceView[] = [
  { id: 1, name: "corp-marketplace" },
  { id: 2, name: "partner-marketplace" },
];

describe("which marketplaces depart from the default", () => {
  test("a global setting is not a departure", () => {
    // prompt-injection is switched on globally. That is the default this page shows above, not an
    // override of it — counting it would say the whole estate departs from itself.
    const rows = overridesOf(chainOverrides, vetterToggles, MARKETPLACES);
    expect(rows.map((row) => row.name)).toEqual(["corp-marketplace", "partner-marketplace"]);
    expect(rows[0]).toMatchObject({ mode: "stop-after-fail", order: ["prompt-injection", "secret-scan"] });
    expect(rows[0]!.vetters).toEqual([]);
    expect(rows[1]!.vetters).toEqual([{ vetter: "secret-scan", enabled: false }]);
  });

  test("a marketplace with nothing set gets no row", () => {
    expect(overridesOf({ modes: [], orders: [] }, [], MARKETPLACES)).toEqual([]);
  });

  test("a setting whose marketplace is gone is still shown, by id and unaddressable", () => {
    const rows = overridesOf(chainOverrides, vetterToggles, []);
    expect(rows.map((row) => row.label)).toEqual(["marketplace #1", "marketplace #2"]);
    expect(rows.every((row) => row.name === undefined)).toBe(true);
  });
});

describe("what a bulk change would do", () => {
  test("the before is the marketplace's own setting, not what it effectively runs", () => {
    // "no mode override → stop-after-fail" is a true statement about what will be stored;
    // "run-all → stop-after-fail" would be a claim about resolution this page cannot make.
    const rows = overridesOf(chainOverrides, vetterToggles, MARKETPLACES);
    expect(
      plannedChanges(["partner-marketplace"], rows, { kind: "set-mode", mode: "stop-after-fail" }),
    ).toEqual([
      {
        marketplace: "partner-marketplace",
        before: "no mode override",
        after: "mode: stop-after-fail",
      },
    ]);
  });

  test("clearing says plainly when there is nothing to clear", () => {
    expect(plannedChanges(["unknown"], [], { kind: "clear" })).toEqual([
      { marketplace: "unknown", before: "no overrides", after: "no overrides — nothing to clear" },
    ]);
  });

  test("switching a vetter names the current override when there is one", () => {
    const rows = overridesOf(chainOverrides, vetterToggles, MARKETPLACES);
    expect(
      plannedChanges(["partner-marketplace"], rows, {
        kind: "set-vetter",
        vetter: "secret-scan",
        enabled: true,
      }),
    ).toEqual([
      {
        marketplace: "partner-marketplace",
        before: "secret-scan: off",
        after: "secret-scan: on",
      },
    ]);
  });
});

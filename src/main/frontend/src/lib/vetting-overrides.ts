/**
 * Which marketplaces depart from the default chain, and in what.
 *
 * Kept apart from the page so the rule that decides what a row says — the part that can be wrong —
 * is testable without a DOM.
 *
 * This is deliberately *not* a resolution: it never asks what a marketplace effectively runs, only
 * whether a setting scoped to it exists and what that setting says. The resolution rule lives in
 * the gateway (`GET /api/marketplaces/{name}/vetting-chain*`) and a second copy here would be free
 * to disagree with the one that decides what actually runs.
 *
 * @Requirements GW_VETTING_0035
 */
import type {
  ChainMode,
  ChainSettingsList,
  MarketplaceView,
  VetterToggle,
} from "@/api/queries";

/** One vetter a marketplace overrides, and what it overrode it to. */
export interface VetterOverride {
  vetter: string;
  enabled: boolean;
}

/** One marketplace that departs from the default, and every way in which it does. */
export interface MarketplaceOverride {
  /** The marketplace's name, or its id when it is no longer registered. */
  label: string;
  /** The name to address it by in a request, absent when it is no longer registered. */
  name?: string;
  mode?: ChainMode;
  order?: string[];
  vetters: VetterOverride[];
}

/** Whether this row overrides anything at all — the condition for it to be a row. */
export function overridesSomething(row: MarketplaceOverride): boolean {
  return row.mode !== undefined || row.order !== undefined || row.vetters.length > 0;
}

/**
 * One row per marketplace that overrides something, in marketplace-name order.
 *
 * A setting whose marketplace is no longer registered still gets a row: a stored override nobody
 * can see is worse than one labelled by its id, and it is the row an administrator has to be able
 * to find in order to understand a name that vanished.
 */
export function overridesOf(
  settings: ChainSettingsList | undefined,
  toggles: VetterToggle[] | undefined,
  marketplaces: MarketplaceView[] | undefined,
): MarketplaceOverride[] {
  const names = new Map<number, string>();
  for (const marketplace of marketplaces ?? []) {
    if (marketplace.id !== undefined && marketplace.name !== undefined) {
      names.set(marketplace.id, marketplace.name);
    }
  }

  const rows = new Map<number, MarketplaceOverride>();
  const row = (id: number): MarketplaceOverride => {
    const existing = rows.get(id);
    if (existing) return existing;
    const name = names.get(id);
    const created: MarketplaceOverride = {
      label: name ?? `marketplace #${id}`,
      name,
      vetters: [],
    };
    rows.set(id, created);
    return created;
  };

  for (const mode of settings?.modes ?? []) {
    if (mode.marketplaceId !== undefined && mode.mode !== undefined) {
      row(mode.marketplaceId).mode = mode.mode;
    }
  }
  for (const order of settings?.orders ?? []) {
    if (order.marketplaceId !== undefined) {
      row(order.marketplaceId).order = order.vetters ?? [];
    }
  }
  for (const toggle of toggles ?? []) {
    if (toggle.marketplaceId !== undefined && toggle.vetter !== undefined) {
      row(toggle.marketplaceId).vetters.push({
        vetter: toggle.vetter,
        enabled: toggle.enabled === true,
      });
    }
  }

  for (const value of rows.values()) {
    value.vetters.sort((a, b) => a.vetter.localeCompare(b.vetter));
  }
  return [...rows.values()]
    .filter(overridesSomething)
    .sort((a, b) => a.label.localeCompare(b.label));
}

/** What a row overrides, as a reader scanning the table needs it: short, and never colour alone. */
export function overrideSummary(row: MarketplaceOverride): string[] {
  const parts: string[] = [];
  if (row.mode !== undefined) parts.push(`mode: ${row.mode}`);
  if (row.order !== undefined) parts.push(`order: ${row.order.join(" → ")}`);
  for (const vetter of row.vetters) {
    parts.push(`${vetter.vetter}: ${vetter.enabled ? "on" : "off"}`);
  }
  return parts;
}

/** What a bulk action would do to one marketplace, said as before → after. */
export interface Change {
  marketplace: string;
  before: string;
  after: string;
}

/**
 * The before/after a confirm step shows, stated in terms of the marketplace's **own** setting —
 * which is exactly what the request writes.
 *
 * Saying it that way rather than as an effective value is what keeps the screen honest: "no
 * override → stop-after-fail" is a true statement about what will be stored, where "run-all →
 * stop-after-fail" would be a claim about resolution this page is not entitled to make.
 */
export function plannedChanges(
  selected: string[],
  overrides: MarketplaceOverride[],
  action: BulkAction,
): Change[] {
  const byName = new Map(overrides.filter((row) => row.name).map((row) => [row.name!, row]));
  return selected.map((marketplace) => {
    const row = byName.get(marketplace);
    switch (action.kind) {
      case "set-mode":
        return {
          marketplace,
          before: row?.mode === undefined ? "no mode override" : `mode: ${row.mode}`,
          after: `mode: ${action.mode}`,
        };
      case "set-order":
        return {
          marketplace,
          before: row?.order === undefined ? "no order override" : `order: ${row.order.join(" → ")}`,
          after: `order: ${action.vetters.join(" → ")}`,
        };
      case "set-vetter": {
        const current = row?.vetters.find((vetter) => vetter.vetter === action.vetter);
        return {
          marketplace,
          before:
            current === undefined
              ? `no override of ${action.vetter}`
              : `${action.vetter}: ${current.enabled ? "on" : "off"}`,
          after: `${action.vetter}: ${action.enabled ? "on" : "off"}`,
        };
      }
      case "clear": {
        const summary = row === undefined ? [] : overrideSummary(row);
        return {
          marketplace,
          before: summary.length === 0 ? "no overrides" : summary.join(", "),
          after: summary.length === 0 ? "no overrides — nothing to clear" : "no overrides",
        };
      }
    }
  });
}

/** The four things bulk edit can do, each carrying exactly what it needs. */
export type BulkAction =
  | { kind: "set-mode"; mode: ChainMode }
  | { kind: "set-order"; vetters: string[] }
  | { kind: "set-vetter"; vetter: string; enabled: boolean }
  | { kind: "clear" };

/** The action as one sentence, for the confirm step's heading and the ledger-facing summary. */
export function actionSentence(action: BulkAction, count: number): string {
  const scope = `${count} ${count === 1 ? "marketplace" : "marketplaces"}`;
  switch (action.kind) {
    case "set-mode":
      return action.mode === "stop-after-fail"
        ? `Stop the chain at the first failure for ${scope}`
        : `Run every vetter for ${scope}`;
    case "set-order":
      return `Set the vetter order for ${scope}`;
    case "set-vetter":
      return `Switch ${action.vetter} ${action.enabled ? "on" : "off"} for ${scope}`;
    case "clear":
      return `Clear every chain override on ${scope}`;
  }
}

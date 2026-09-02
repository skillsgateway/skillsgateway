/**
 * Reading a status out of an audit-ledger row.
 *
 * The ledger is deliberately flat — one `event` string per row, with the vetting outcome
 * buried in a free-text `detail` such as `trigger=ingestion; outcome=BLOCKED; …`. A reader
 * scanning the table cannot tell a blocked snapshot from a clean one, which is the exact
 * complaint behind #221/#224: the same verdict that paints a marketplace row red is invisible
 * here. This module derives a coarse status from the two columns the server already writes, so
 * the row can carry the same colour treatment the rest of the portal uses for a verdict.
 *
 * It never invents policy. The mapping is a legibility aid over what the ledger says; the
 * authoritative outcome is still the vetting record. The lossy `detail` shape and the
 * `human` actor-type on automated `vetting` principals are backend concerns tracked in #221 —
 * this only makes the existing text legible, it does not correct it.
 *
 * @Requirements GW_0030
 */

export type AuditStatus = "clear" | "warn" | "blocked" | "neutral";

/** Events that always read as a refusal/violation, regardless of any detail text. */
const BLOCKED_EVENTS = new Set([
  "snapshot-approval-refused",
  "snapshot-rejected",
  "snapshot-revoked",
  "snapshot-unpublished",
  "snapshot-unpublish-failed",
  "revet-violation",
  "revet-violation-affected",
  "private-key-block",
  "marketplace-push-ingest-failed",
]);

/** Events that always read as a clean/positive act. */
const CLEAR_EVENTS = new Set([
  "snapshot-approved",
  "snapshot-restored",
  "revet-clear",
]);

/** Events that are an unresolved or partial outcome — worth flagging amber, not red. */
const WARN_EVENTS = new Set([
  "revet-inconclusive",
  "waiver-created",
  "waiver-applied",
  "waiver-expired",
  "waiver-revoked",
]);

/**
 * Pulls `outcome=…` out of a `vetting-completed` detail string. The server writes the outcome
 * lower-cased (`outcome=blocked`), so the match is case-insensitive and normalised.
 */
function outcomeFromDetail(detail: string | undefined): AuditStatus | undefined {
  if (!detail) return undefined;
  const match = /outcome=([A-Za-z_]+)/.exec(detail);
  if (!match?.[1]) return undefined;
  switch (match[1].toUpperCase()) {
    case "CLEAR":
      return "clear";
    case "CLEAR_WITH_WAIVERS":
      return "warn";
    case "BLOCKED":
      return "blocked";
    default:
      return undefined;
  }
}

/**
 * The status a ledger row should read as. `event` decides first; a `vetting-completed` row
 * then defers to the `outcome=` its detail carries. Everything else — a fetch, an ingest, a
 * token lifecycle event — is neutral: legible, uncoloured, not a verdict.
 */
export function auditStatus(row: Record<string, unknown>): AuditStatus {
  const event = typeof row.event === "string" ? row.event : "";
  const detail = typeof row.detail === "string" ? row.detail : undefined;

  if (event === "vetting-completed") {
    return outcomeFromDetail(detail) ?? "neutral";
  }
  if (BLOCKED_EVENTS.has(event)) return "blocked";
  if (CLEAR_EVENTS.has(event)) return "clear";
  if (WARN_EVENTS.has(event)) return "warn";
  return "neutral";
}

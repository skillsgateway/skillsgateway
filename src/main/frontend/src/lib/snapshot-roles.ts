import type { Snapshot } from "@/api/queries";

export const SNAPSHOT_TABS = ["vetting", "contents", "diff", "inventory", "provenance"] as const;
export type SnapshotTab = (typeof SNAPSHOT_TABS)[number];

export function parseSnapshotTab(value: string | null): SnapshotTab {
  return (SNAPSHOT_TABS as readonly string[]).includes(value ?? "")
    ? (value as SnapshotTab)
    : "vetting";
}

/** A revoked snapshot is decidable again: the retraction was made without a person. */
export function isDecidable(snapshot: Snapshot): boolean {
  return (snapshot.state === "held" || snapshot.state === "revoked") && !snapshot.deletedAt;
}

/**
 * A snapshot's vetting findings, read per file for the Contents view: which lines of a file
 * findings locate, what each says, and whether an active waiver covers it.
 *
 * @Requirements GW_APPROVAL_0029
 */
import type { VettingView } from "@/api/queries";

export type Severity = "info" | "low" | "medium" | "high" | "critical";

const ORDER: readonly Severity[] = ["critical", "high", "medium", "low", "info"];

export interface FileFinding {
  vetter: string;
  ruleId: string;
  severity: Severity;
  message: string;
  location: string;
  /** The located line; null when the location names none. */
  line: number | null;
  waived: { by: string; until: string | null } | null;
}

/** A location split as `WaiverScope.pathOf` splits it on the server: the digits after the last colon. */
export function parseLocation(location: string): { path: string; line: number | null } {
  const colon = location.lastIndexOf(":");
  if (colon <= 0 || colon === location.length - 1) return { path: location, line: null };
  const suffix = location.slice(colon + 1);
  if (!/^\d+$/.test(suffix)) return { path: location, line: null };
  return { path: location.slice(0, colon), line: Number(suffix) };
}

/** Every finding of the latest run, by the path its location names, in chain and finding order. */
export function findingsByPath(view: VettingView | undefined): Map<string, FileFinding[]> {
  const waivers = new Map(
    (view?.suppressed ?? []).map((s) => [`${s.vetter}\0${s.ruleId}\0${s.location}`, s]),
  );
  const byPath = new Map<string, FileFinding[]>();
  for (const verdict of view?.run?.verdicts ?? []) {
    for (const finding of verdict.findings ?? []) {
      if (!finding.location) continue;
      const { path, line } = parseLocation(finding.location);
      const waiver = waivers.get(`${verdict.vetter}\0${finding.id}\0${finding.location}`);
      const list = byPath.get(path) ?? [];
      list.push({
        vetter: verdict.vetter ?? "",
        ruleId: finding.id ?? "",
        severity: finding.severity ?? "info",
        message: finding.message ?? "",
        location: finding.location,
        line,
        waived: waiver ? { by: waiver.approvedBy ?? "", until: waiver.expiresAt ?? null } : null,
      });
      byPath.set(path, list);
    }
  }
  return byPath;
}

export function highestSeverity(findings: readonly FileFinding[]): Severity | null {
  for (const severity of ORDER) {
    if (findings.some((finding) => finding.severity === severity)) return severity;
  }
  return null;
}

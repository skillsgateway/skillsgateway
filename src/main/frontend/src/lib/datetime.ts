/**
 * Portal timestamp formatting.
 *
 * Timestamps arrive as ISO-8601 instants and are shown in the reader's own locale
 * and zone. The exact wire value is never discarded — {@link Timestamp} keeps it in
 * the `<time datetime>` attribute and in the hover title — so an auditor reading a
 * ledger entry can still recover the precise instant it was recorded at.
 */

/** What every surface shows where a timestamp is absent. */
export const ABSENT = "—";

const instantFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
});

const dayFormat = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

/** Full precision, including the zone, for the hover title. */
const preciseFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: "full",
  timeStyle: "long",
});

function parse(value: string | null | undefined): Date | null {
  if (!value) {
    return null;
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

/** Date and time, e.g. "18 Aug 2026, 02:41". */
export function formatInstant(value: string | null | undefined): string {
  const parsed = parse(value);
  // An unparseable value is shown verbatim rather than hidden: a timestamp the portal
  // cannot read is a bug worth seeing, not one worth rendering as an em dash.
  return parsed ? instantFormat.format(parsed) : (value || ABSENT);
}

/** Date only, for deadlines whose time of day carries no meaning. */
export function formatDay(value: string | null | undefined): string {
  const parsed = parse(value);
  return parsed ? dayFormat.format(parsed) : (value || ABSENT);
}

/** The tooltip: full local rendering, then the untouched wire value. */
export function formatPrecise(value: string): string {
  const parsed = parse(value);
  return parsed ? `${preciseFormat.format(parsed)} · ${value}` : value;
}

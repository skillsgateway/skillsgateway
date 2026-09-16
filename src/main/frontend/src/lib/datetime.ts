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

const relativeFormat = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });

/**
 * Largest-first, so the boundary below each one is where the next takes over. Ordering matters:
 * "3 days ago" is the answer a reader wants, not "72 hours ago".
 */
const RELATIVE_UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["year", 365 * 24 * 60 * 60],
  ["month", 30 * 24 * 60 * 60],
  ["week", 7 * 24 * 60 * 60],
  ["day", 24 * 60 * 60],
  ["hour", 60 * 60],
  ["minute", 60],
];

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

/**
 * How long ago, in the reader's locale, e.g. "3 hours ago". The precise instant is never lost — the
 * caller keeps it in the `datetime` attribute and the tooltip — so this is a reading aid rather
 * than the value itself.
 */
export function formatRelative(value: string | null | undefined): string {
  const parsed = parse(value);
  if (!parsed) {
    return value || ABSENT;
  }
  const seconds = (parsed.getTime() - Date.now()) / 1000;
  const magnitude = Math.abs(seconds);
  for (const [unit, size] of RELATIVE_UNITS) {
    if (magnitude >= size) {
      return relativeFormat.format(Math.round(seconds / size), unit);
    }
  }
  // Under a minute. "now" reads better than "in 0 seconds", which is what rounding would give
  // for a value recorded during the request that rendered the page.
  return relativeFormat.format(0, "minute");
}

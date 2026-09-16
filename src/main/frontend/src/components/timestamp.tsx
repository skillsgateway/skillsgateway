import { ABSENT, formatDay, formatInstant, formatPrecise, formatRelative } from "@/lib/datetime";

/**
 * A timestamp rendered for humans without losing the machine value: the ISO-8601
 * instant stays in `datetime` and in the hover title.
 *
 * `relative` swaps the visible text for "3 hours ago" where recency is the question being asked;
 * `absent` replaces the em dash where a page has a better word for "there isn't one" ("never").
 * Both leave the `datetime` attribute and the precise tooltip exactly as they are, which is the
 * part no page should reinvent.
 */
export function Timestamp({
  value,
  dayOnly = false,
  relative = false,
  absent = ABSENT,
  className,
}: {
  value: string | null | undefined;
  dayOnly?: boolean;
  relative?: boolean;
  absent?: string;
  className?: string;
}) {
  if (!value) {
    return <span className={className}>{absent}</span>;
  }
  const text = relative ? formatRelative(value) : dayOnly ? formatDay(value) : formatInstant(value);
  return (
    <time dateTime={value} title={formatPrecise(value)} className={className}>
      {text}
    </time>
  );
}

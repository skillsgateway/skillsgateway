import { ABSENT, formatDay, formatInstant, formatPrecise } from "@/lib/datetime";

/**
 * A timestamp rendered for humans without losing the machine value: the ISO-8601
 * instant stays in `datetime` and in the hover title.
 */
export function Timestamp({
  value,
  dayOnly = false,
  className,
}: {
  value: string | null | undefined;
  dayOnly?: boolean;
  className?: string;
}) {
  if (!value) {
    return <span className={className}>{ABSENT}</span>;
  }
  const text = dayOnly ? formatDay(value) : formatInstant(value);
  return (
    <time dateTime={value} title={formatPrecise(value)} className={className}>
      {text}
    </time>
  );
}

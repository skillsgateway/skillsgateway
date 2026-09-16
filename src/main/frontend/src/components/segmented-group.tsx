import { useId } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export type SegmentedOption<T extends string | number> = {
  value: T;
  label: string;
};

/**
 * A small set of mutually exclusive choices rendered as themed buttons.
 *
 * Used instead of a native `<select>` because the browser paints a select's open list with its
 * own chrome, which ignores the portal's dark theme and leaves the unselected options
 * unreadable. Buttons are ours to style, so both themes look the same.
 *
 * The pressed button is the current value: `aria-pressed` is the contract the tests query, and
 * every option is reachable by Tab.
 */
export function SegmentedGroup<T extends string | number>({
  label,
  hideLabel = false,
  value,
  options,
  onChange,
  className,
  describedBy,
}: {
  label: string;
  /** Keep the name for assistive technology only — for a group whose meaning the row already carries. */
  hideLabel?: boolean;
  value: T;
  options: readonly SegmentedOption<T>[];
  onChange: (value: T) => void;
  className?: string;
  describedBy?: string;
}) {
  const labelId = useId();
  const group = (
    <div
      role="group"
      aria-label={hideLabel ? label : undefined}
      aria-labelledby={hideLabel ? undefined : labelId}
      aria-describedby={describedBy}
      className={cn("flex flex-wrap gap-1", hideLabel && className)}
    >
      {options.map((option) => (
        <Button
          key={option.value}
          type="button"
          size="sm"
          variant={option.value === value ? "default" : "outline"}
          aria-pressed={option.value === value}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </Button>
      ))}
    </div>
  );
  if (hideLabel) return group;
  return (
    <div className={cn("space-y-2", className)}>
      <span id={labelId} className="block text-sm leading-none font-medium select-none">
        {label}
      </span>
      {group}
    </div>
  );
}

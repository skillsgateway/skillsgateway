import { Fragment } from "react";
import { revealControls, type Control, type Revealed } from "@/lib/visible-controls";

/** One format character, shown: its code point in a bordered marker, its name for a screen reader. */
export function ControlToken({ control }: { control: Control }) {
  return (
    <span
      data-control={control.token}
      title={`${control.name} ${control.token}`}
      className="mx-px rounded-sm border border-destructive/70 bg-background px-0.5 font-mono text-[0.85em] font-medium not-italic whitespace-nowrap text-foreground"
    >
      {control.token}
      <span className="sr-only"> ({control.name})</span>
    </span>
  );
}

/** Text already split by `revealControls`, so a caller that needs the count scans the text once. */
export function RevealedText({ revealed }: { revealed: Revealed }) {
  if (revealed.controls === 0) return <>{revealed.parts[0] ?? ""}</>;
  return (
    <>
      {revealed.parts.map((part, index) =>
        typeof part === "string" ? (
          <Fragment key={index}>{part}</Fragment>
        ) : (
          <ControlToken key={index} control={part} />
        ),
      )}
    </>
  );
}

/**
 * Upstream text with every invisible or bidirectional control character shown as a token rather
 * than applied. Display only: callers keep the original string for keys, callbacks and addresses.
 *
 * @Requirements GW_APPROVAL_0037
 */
export function VisibleText({ text }: { text: string | null | undefined }) {
  if (!text) return <>{text ?? ""}</>;
  return <RevealedText revealed={revealControls(text)} />;
}

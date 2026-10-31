import { useEffect, useId, useRef } from "react";
import { highestSeverity, type FileFinding, type Severity } from "@/lib/file-findings";
import { formatDay } from "@/lib/datetime";
import { revealControls, showControls } from "@/lib/visible-controls";
import { RevealedText, VisibleText } from "@/components/visible-text";

/** The tint of a marked line. The severity word leads each note, so colour is never the only signal. */
const TINT: Record<Severity, string> = {
  critical: "border-destructive bg-destructive/10",
  high: "border-destructive bg-destructive/10",
  medium: "border-primary bg-primary/10",
  low: "border-muted-foreground/50 bg-muted",
  info: "border-muted-foreground/50 bg-muted",
};

/** One finding as the reviewer reads it: severity, vetter, rule, message, and any waiver. */
function Note({ id, finding, prefix }: { id: string; finding: FileFinding; prefix?: string }) {
  const high = finding.severity === "high" || finding.severity === "critical";
  return (
    <div
      id={id}
      role="note"
      // On the page background, not the line's tint: the text keeps its contrast in both themes. A
      // waived note is set apart by its rule and its words, never by fading the words.
      className={`ml-12 border-l-2 bg-background py-1 pr-2 pl-3 font-sans text-xs whitespace-normal ${
        finding.waived ? "border-dashed border-muted-foreground" : high ? "border-destructive" : "border-primary"
      }`}
    >
      {prefix ? <span className="text-muted-foreground">{prefix} — </span> : null}
      <span className={`font-medium ${high && !finding.waived ? "text-destructive" : ""}`}>{finding.severity}</span>
      {" · "}
      <span className="font-mono">{finding.vetter}</span>
      {" · "}
      <span className="font-mono">{finding.ruleId}</span>
      {" — "}
      <span>
        <VisibleText text={finding.message} />
      </span>
      {finding.waived ? (
        <span className="text-muted-foreground">
          {" "}
          (waived by {finding.waived.by}
          {finding.waived.until ? ` until ${formatDay(finding.waived.until)}` : ""})
        </span>
      ) : null}
    </div>
  );
}

/**
 * Written beneath a line that holds a format character, so the line is marked in words even where
 * no vetter looked; the tokens in the line show which characters and where.
 *
 * @Requirements GW_APPROVAL_0038
 */
function ControlsMarker({ id }: { id: string }) {
  return (
    <div
      id={id}
      className="ml-12 border-l-2 border-dashed border-destructive/70 bg-background py-1 pr-2 pl-3 font-sans text-xs whitespace-normal"
    >
      Contains invisible or bidirectional control characters, shown as ⟨U+…⟩.
    </div>
  );
}

/**
 * A file's text as numbered lines, with each line a finding locates marked by severity and its
 * findings written out beneath it. The text is inert: numbered and marked, never interpreted.
 *
 * Each line is focusable (`tabIndex=-1`) and described by its findings, so moving to a line from
 * the file's summary or from an addressed link reads what was found there.
 *
 * @Requirements GW_APPROVAL_0029, GW_APPROVAL_0030, GW_APPROVAL_0036, GW_VETTING_0072
 */
export function SourceView({
  path,
  text,
  findings,
  truncated = false,
  firstLine = 1,
  focusLine = null,
  focusKey = 0,
}: {
  path: string;
  text: string;
  findings: readonly FileFinding[];
  truncated?: boolean;
  /** The number of the text's first line: above 1 for a part of a large file (GW_APPROVAL_0036). */
  firstLine?: number;
  focusLine?: number | null;
  /** Changes when the same line is asked for again, so it is focused again. */
  focusKey?: number;
}) {
  const id = useId();
  const lines = text.split(/\r?\n/);
  if (lines.length > 1 && lines[lines.length - 1] === "") lines.pop();
  const byLine = new Map<number, FileFinding[]>();
  for (const finding of findings) {
    // Every line the finding names, not only its location's (GW_VETTING_0072).
    for (const n of finding.lines ?? (finding.line === null ? [] : [finding.line])) {
      if (n < 1) continue;
      byLine.set(n, [...(byLine.get(n) ?? []), finding]);
    }
  }
  const last = firstLine + lines.length - 1;
  const beyond = findings.filter(
    (finding) => finding.line !== null && finding.line >= 1 && (finding.line < firstLine || finding.line > last),
  );
  const refs = useRef(new Map<number, HTMLElement>());

  useEffect(() => {
    if (focusLine === null) return;
    const line = refs.current.get(focusLine);
    if (!line) return;
    line.scrollIntoView?.({ block: "center" });
    line.focus({ preventScroll: true });
  }, [focusLine, focusKey, text]);

  return (
    <div className="space-y-2">
      <ol
        aria-label={`Lines of ${showControls(path)}`}
        className="overflow-x-auto rounded-md border bg-muted/40 py-2 font-mono text-xs"
      >
        {lines.map((content, index) => {
          const n = firstLine + index;
          const onLine = byLine.get(n) ?? [];
          const severity = highestSeverity(onLine);
          const noteIds = onLine.map((_, i) => `${id}-L${n}-${i}`);
          const shown = revealControls(content);
          const markerId = shown.controls > 0 ? `${id}-L${n}-controls` : null;
          const describedBy = markerId ? [...noteIds, markerId] : noteIds;
          return (
            <li
              key={n}
              data-line={n}
              data-severity={severity ?? undefined}
              data-controls={shown.controls > 0 ? shown.controls : undefined}
              tabIndex={-1}
              ref={(element) => {
                if (element) refs.current.set(n, element);
                else refs.current.delete(n);
              }}
              aria-describedby={describedBy.length > 0 ? describedBy.join(" ") : undefined}
              className={`scroll-mt-4 border-l-2 outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                severity ? TINT[severity] : "border-transparent"
              }`}
            >
              <div className="flex">
                <span
                  aria-hidden
                  className={`w-10 shrink-0 pr-2 text-right select-none ${
                    severity ? "font-medium text-foreground" : "text-muted-foreground"
                  }`}
                >
                  {n}
                </span>
                <span className="min-w-0 pr-3 break-all whitespace-pre-wrap">
                  {content === "" ? " " : <RevealedText revealed={shown} />}
                </span>
              </div>
              {onLine.map((finding, i) => (
                <Note key={noteIds[i]} id={noteIds[i]!} finding={finding} />
              ))}
              {markerId ? <ControlsMarker id={markerId} /> : null}
            </li>
          );
        })}
      </ol>
      {beyond.map((finding, i) => (
        <Note
          key={`${id}-beyond-${i}`}
          id={`${id}-beyond-${i}`}
          finding={finding}
          prefix={`Line ${finding.line} is ${finding.line! < firstLine ? "before" : "beyond"} the part shown${
            truncated ? " (the file is truncated)" : ""
          }`}
        />
      ))}
    </div>
  );
}

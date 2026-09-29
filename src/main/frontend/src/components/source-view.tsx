import { useEffect, useId, useRef } from "react";
import { highestSeverity, type FileFinding, type Severity } from "@/lib/file-findings";
import { formatDay } from "@/lib/datetime";

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
      className={`ml-12 border-l-2 py-1 pr-2 pl-3 font-sans text-xs whitespace-normal ${
        finding.waived ? "border-muted-foreground/50 opacity-75" : high ? "border-destructive" : "border-primary"
      }`}
    >
      {prefix ? <span className="text-muted-foreground">{prefix} — </span> : null}
      <span className={`font-medium ${high && !finding.waived ? "text-destructive" : ""}`}>{finding.severity}</span>
      {" · "}
      <span className="font-mono">{finding.vetter}</span>
      {" · "}
      <span className="font-mono">{finding.ruleId}</span>
      {" — "}
      <span>{finding.message}</span>
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
 * A file's text as numbered lines, with each line a finding locates marked by severity and its
 * findings written out beneath it. The text is inert: numbered and marked, never interpreted.
 *
 * Each line is focusable (`tabIndex=-1`) and described by its findings, so moving to a line from
 * the file's summary or from an addressed link reads what was found there.
 *
 * @Requirements GW_APPROVAL_0029, GW_APPROVAL_0030
 */
export function SourceView({
  path,
  text,
  findings,
  truncated = false,
  focusLine = null,
  focusKey = 0,
}: {
  path: string;
  text: string;
  findings: readonly FileFinding[];
  truncated?: boolean;
  focusLine?: number | null;
  /** Changes when the same line is asked for again, so it is focused again. */
  focusKey?: number;
}) {
  const id = useId();
  const lines = text.split(/\r?\n/);
  if (lines.length > 1 && lines[lines.length - 1] === "") lines.pop();
  const byLine = new Map<number, FileFinding[]>();
  for (const finding of findings) {
    if (finding.line === null || finding.line < 1) continue;
    byLine.set(finding.line, [...(byLine.get(finding.line) ?? []), finding]);
  }
  const beyond = findings.filter((finding) => finding.line !== null && finding.line > lines.length);
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
        aria-label={`Lines of ${path}`}
        className="overflow-x-auto rounded-md border bg-muted/40 py-2 font-mono text-xs"
      >
        {lines.map((content, index) => {
          const n = index + 1;
          const onLine = byLine.get(n) ?? [];
          const severity = highestSeverity(onLine);
          const noteIds = onLine.map((_, i) => `${id}-L${n}-${i}`);
          return (
            <li
              key={n}
              data-line={n}
              data-severity={severity ?? undefined}
              tabIndex={-1}
              ref={(element) => {
                if (element) refs.current.set(n, element);
                else refs.current.delete(n);
              }}
              aria-describedby={noteIds.length > 0 ? noteIds.join(" ") : undefined}
              className={`scroll-mt-4 border-l-2 outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                severity ? TINT[severity] : "border-transparent"
              }`}
            >
              <div className="flex">
                <span aria-hidden className="w-10 shrink-0 pr-2 text-right text-muted-foreground select-none">
                  {n}
                </span>
                <span className="min-w-0 pr-3 break-all whitespace-pre-wrap">{content === "" ? " " : content}</span>
              </div>
              {onLine.map((finding, i) => (
                <Note key={noteIds[i]} id={noteIds[i]!} finding={finding} />
              ))}
            </li>
          );
        })}
      </ol>
      {beyond.map((finding, i) => (
        <Note
          key={`${id}-beyond-${i}`}
          id={`${id}-beyond-${i}`}
          finding={finding}
          prefix={`Line ${finding.line} is beyond the part shown${truncated ? " (the file is truncated)" : ""}`}
        />
      ))}
    </div>
  );
}

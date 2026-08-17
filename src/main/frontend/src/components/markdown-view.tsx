import { Fragment, type ReactNode } from "react";

/**
 * Inert Markdown rendering for the preview pane: a small subset (headings, paragraphs, lists,
 * blockquotes, fenced and inline code, bold/italic) mapped straight to React elements.
 *
 * There is deliberately no HTML pipeline at all — no dangerouslySetInnerHTML, no sanitizer to
 * mis-configure. React escapes every text node, so HTML embedded in a hostile SKILL.md renders
 * as visible text rather than as markup, and links render as non-navigating text: this surface
 * inspects content, it never executes or follows it.
 *
 * @Requirements GW_0082
 */

/** Inline spans: `code`, **bold**, *italic*, and [text](url) shown inertly as "text (url)". */
function inline(text: string, key: number): ReactNode {
  const parts: ReactNode[] = [];
  const pattern = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\*[^*]+\*)|(\[[^\]]+\]\([^)]+\))/g;
  let last = 0;
  let index = 0;
  for (const match of text.matchAll(pattern)) {
    if (match.index > last) parts.push(text.slice(last, match.index));
    const token = match[0];
    index += 1;
    if (token.startsWith("`")) {
      parts.push(
        <code key={index} className="rounded bg-muted px-1 font-mono text-[0.85em]">
          {token.slice(1, -1)}
        </code>,
      );
    } else if (token.startsWith("**")) {
      parts.push(<strong key={index}>{token.slice(2, -2)}</strong>);
    } else if (token.startsWith("*")) {
      parts.push(<em key={index}>{token.slice(1, -1)}</em>);
    } else {
      // A link stays a description, never a navigation: text plus the target, both as text.
      const link = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(token);
      parts.push(
        <span key={index}>
          {link?.[1]}
          <span className="text-muted-foreground"> ({link?.[2]})</span>
        </span>,
      );
    }
    last = match.index + token.length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return <Fragment key={key}>{parts}</Fragment>;
}

const HEADING_SIZES = [
  "text-lg font-semibold",
  "text-base font-semibold",
  "text-sm font-semibold",
] as const;

export function MarkdownView({ text }: { text: string }) {
  const lines = text.split("\n");
  const blocks: ReactNode[] = [];
  let paragraph: string[] = [];
  let list: string[] = [];
  let code: string[] | null = null;
  let key = 0;

  const flushParagraph = () => {
    if (paragraph.length > 0) {
      blocks.push(
        <p key={key++} className="text-sm leading-relaxed">
          {inline(paragraph.join(" "), key)}
        </p>,
      );
      paragraph = [];
    }
  };
  const flushList = () => {
    if (list.length > 0) {
      blocks.push(
        <ul key={key++} className="list-disc space-y-1 pl-5 text-sm">
          {list.map((item, i) => (
            <li key={i}>{inline(item, i)}</li>
          ))}
        </ul>,
      );
      list = [];
    }
  };

  for (const line of lines) {
    if (code !== null) {
      if (line.startsWith("```")) {
        blocks.push(
          <pre key={key++} className="overflow-x-auto rounded-md border bg-muted p-3 font-mono text-xs">
            {code.join("\n")}
          </pre>,
        );
        code = null;
      } else {
        code.push(line);
      }
      continue;
    }
    if (line.startsWith("```")) {
      flushParagraph();
      flushList();
      code = [];
      continue;
    }
    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) {
      flushParagraph();
      flushList();
      const marks = heading[1] ?? "#";
      const level = Math.min(marks.length, 3) - 1;
      blocks.push(
        <div key={key++} role="heading" aria-level={marks.length} className={HEADING_SIZES[level]}>
          {inline(heading[2] ?? "", key)}
        </div>,
      );
      continue;
    }
    const item = /^\s*(?:[-*+]|\d+\.)\s+(.*)$/.exec(line);
    if (item) {
      flushParagraph();
      list.push(item[1] ?? "");
      continue;
    }
    if (line.startsWith(">")) {
      flushParagraph();
      flushList();
      blocks.push(
        <blockquote key={key++} className="border-l-2 pl-3 text-sm text-muted-foreground">
          {inline(line.replace(/^>\s?/, ""), key)}
        </blockquote>,
      );
      continue;
    }
    if (line.trim() === "") {
      flushParagraph();
      flushList();
      continue;
    }
    flushList();
    paragraph.push(line);
  }
  if (code !== null) {
    blocks.push(
      <pre key={key++} className="overflow-x-auto rounded-md border bg-muted p-3 font-mono text-xs">
        {code.join("\n")}
      </pre>,
    );
  }
  flushParagraph();
  flushList();
  return <div className="space-y-3">{blocks}</div>;
}

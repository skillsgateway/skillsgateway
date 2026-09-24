/**
 * Re-indents JSON by its tokens, never by parsing it into values. `JSON.parse` keeps only the last
 * of a duplicated key and rewrites number spellings — exactly the differences a hostile manifest
 * can use to show a reviewer one document and a client another — so every token here is copied
 * as written, in order, and only the whitespace between tokens changes (GW_INGEST_0032).
 *
 * Returns null for anything that does not tokenise as JSON, which the caller shows as stored.
 */
export function formatJson(text: string, indent = "  "): string | null {
  const tokens = tokenize(text);
  if (tokens === null) return null;
  const closer: Record<string, string> = { "{": "}", "[": "]" };
  const open: string[] = [];
  let out = "";
  const line = () => "\n" + indent.repeat(open.length);
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i]!;
    if (token === "{" || token === "[") {
      if (tokens[i + 1] === closer[token]) {
        out += token + closer[token];
        i++;
        continue;
      }
      open.push(closer[token]!);
      out += token + line();
    } else if (token === "}" || token === "]") {
      if (open.pop() !== token) return null;
      out += line() + token;
    } else if (token === ",") {
      if (open.length === 0) return null;
      out += "," + line();
    } else if (token === ":") {
      out += ": ";
    } else {
      out += token;
    }
  }
  return open.length === 0 ? out : null;
}

const LITERAL = /^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)$/;

function tokenize(text: string): string[] | null {
  const tokens: string[] = [];
  let i = 0;
  while (i < text.length) {
    const c = text[i]!;
    if (c === " " || c === "\t" || c === "\n" || c === "\r") {
      i++;
    } else if ("{}[]:,".includes(c)) {
      tokens.push(c);
      i++;
    } else if (c === '"') {
      let j = i + 1;
      while (j < text.length && text[j] !== '"') {
        // A raw control character is not JSON; an escape takes the next character with it.
        if (text.charCodeAt(j) < 0x20) return null;
        j += text[j] === "\\" ? 2 : 1;
      }
      if (j >= text.length) return null;
      tokens.push(text.slice(i, j + 1));
      i = j + 1;
    } else {
      let j = i;
      while (j < text.length && /[A-Za-z0-9+\-.]/.test(text[j]!)) j++;
      const literal = text.slice(i, j);
      if (!LITERAL.test(literal)) return null;
      tokens.push(literal);
      i = j;
    }
  }
  return tokens;
}

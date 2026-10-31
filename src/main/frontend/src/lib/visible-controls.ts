/**
 * Unicode format characters (general category Cf) in upstream text, made visible: bidirectional
 * controls that reorder what is around them, zero-width characters that render as nothing, the
 * byte-order mark and the tag block. Each becomes a token naming its code point; nothing is
 * stripped, and the original string is left for everything but display.
 *
 * @Requirements GW_APPROVAL_0037
 */

/** One format character as the reviewer is shown it. */
export interface Control {
  codePoint: number;
  /** `⟨U+202E⟩`: the same in a rendered marker and in an attribute string. */
  token: string;
  /** What the character is, for a screen reader and a tooltip. */
  name: string;
}

export interface Revealed {
  /** The text in order, each control replaced by its token; `[text]` when there is none. */
  parts: readonly (string | Control)[];
  controls: number;
}

const FORMAT = /\p{Cf}/gu;

const NAMES: ReadonlyMap<number, string> = new Map([
  [0x00ad, "soft hyphen"],
  [0x061c, "Arabic letter mark"],
  [0x180e, "Mongolian vowel separator"],
  [0x200b, "zero-width space"],
  [0x200c, "zero-width non-joiner"],
  [0x200d, "zero-width joiner"],
  [0x200e, "left-to-right mark"],
  [0x200f, "right-to-left mark"],
  [0x202a, "left-to-right embedding"],
  [0x202b, "right-to-left embedding"],
  [0x202c, "pop directional formatting"],
  [0x202d, "left-to-right override"],
  [0x202e, "right-to-left override"],
  [0x2060, "word joiner"],
  [0x2061, "function application"],
  [0x2062, "invisible times"],
  [0x2063, "invisible separator"],
  [0x2064, "invisible plus"],
  [0x2066, "left-to-right isolate"],
  [0x2067, "right-to-left isolate"],
  [0x2068, "first strong isolate"],
  [0x2069, "pop directional isolate"],
  [0xfeff, "zero-width no-break space (byte-order mark)"],
  [0xe0001, "language tag"],
  [0xe007f, "cancel tag"],
]);

const hex = (codePoint: number) => codePoint.toString(16).toUpperCase().padStart(4, "0");

/** What a format character is: its name, or the ASCII a tag character hides. */
export function controlName(codePoint: number): string {
  const named = NAMES.get(codePoint);
  if (named) return named;
  if (codePoint >= 0xe0020 && codePoint <= 0xe007e) {
    return `tag character "${String.fromCharCode(codePoint - 0xe0000)}"`;
  }
  return "format character";
}

/** The vetter's pictographic ranges (`PromptInjectionVetter.pictographic`), so both agree. */
function pictographic(codePoint: number | undefined): boolean {
  if (codePoint === undefined) return false;
  return (codePoint >= 0x2190 && codePoint <= 0x2bff) || (codePoint >= 0x1f000 && codePoint < 0xe0000);
}

function codePointBefore(text: string, index: number): { codePoint: number; at: number } | null {
  if (index <= 0) return null;
  const low = text.charCodeAt(index - 1);
  if (low >= 0xdc00 && low <= 0xdfff && index >= 2) {
    const high = text.charCodeAt(index - 2);
    if (high >= 0xd800 && high <= 0xdbff) return { codePoint: text.codePointAt(index - 2)!, at: index - 2 };
  }
  return { codePoint: low, at: index - 1 };
}

/**
 * A zero-width joiner between two pictographs is part of one emoji (man, joiner, laptop), as
 * `PromptInjectionVetter.emojiJoiner` reads it; a variation selector or skin tone may sit before it.
 */
function emojiJoiner(text: string, index: number): boolean {
  let before = codePointBefore(text, index);
  if (before && (before.codePoint === 0xfe0f || (before.codePoint >= 0x1f3fb && before.codePoint <= 0x1f3ff))) {
    before = codePointBefore(text, before.at);
  }
  return pictographic(before?.codePoint) && pictographic(text.codePointAt(index + 1));
}

/** The text split around its format characters, in one pass. */
export function revealControls(text: string): Revealed {
  const parts: (string | Control)[] = [];
  let last = 0;
  let controls = 0;
  for (const match of text.matchAll(FORMAT)) {
    const index = match.index;
    if (match[0] === "\u200d" && emojiJoiner(text, index)) continue;
    const codePoint = match[0].codePointAt(0)!;
    if (index > last) parts.push(text.slice(last, index));
    parts.push({ codePoint, token: `⟨U+${hex(codePoint)}⟩`, name: controlName(codePoint) });
    controls += 1;
    last = index + match[0].length;
  }
  if (controls === 0) return { parts: text === "" ? [] : [text], controls: 0 };
  if (last < text.length) parts.push(text.slice(last));
  return { parts, controls };
}

/** The text with each format character as its token, for a `title` or an `aria-label`. */
export function showControls(text: string): string {
  const { parts, controls } = revealControls(text);
  if (controls === 0) return text;
  return parts.map((part) => (typeof part === "string" ? part : part.token)).join("");
}

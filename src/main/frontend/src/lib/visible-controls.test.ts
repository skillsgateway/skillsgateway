import { expect, test } from "vitest";
import { controlName, revealControls, showControls } from "./visible-controls";

const hex = (codePoint: number) => codePoint.toString(16).toUpperCase().padStart(4, "0");

/** One character of each class the vetter flags, plus the rest of Unicode's format category. */
const CLASSES: readonly [string, number, RegExp][] = [
  ["left-to-right embedding", 0x202a, /left-to-right embedding/],
  ["right-to-left embedding", 0x202b, /right-to-left embedding/],
  ["pop directional formatting", 0x202c, /pop directional formatting/],
  ["left-to-right override", 0x202d, /left-to-right override/],
  ["right-to-left override", 0x202e, /right-to-left override/],
  ["left-to-right isolate", 0x2066, /left-to-right isolate/],
  ["right-to-left isolate", 0x2067, /right-to-left isolate/],
  ["first strong isolate", 0x2068, /first strong isolate/],
  ["pop directional isolate", 0x2069, /pop directional isolate/],
  ["left-to-right mark", 0x200e, /left-to-right mark/],
  ["right-to-left mark", 0x200f, /right-to-left mark/],
  ["Arabic letter mark", 0x061c, /Arabic letter mark/],
  ["zero-width space", 0x200b, /zero-width space/],
  ["zero-width non-joiner", 0x200c, /zero-width non-joiner/],
  ["zero-width joiner", 0x200d, /zero-width joiner/],
  ["word joiner", 0x2060, /word joiner/],
  ["invisible times", 0x2062, /invisible times/],
  ["invisible plus", 0x2064, /invisible plus/],
  ["byte-order mark", 0xfeff, /byte-order mark/],
  ["soft hyphen", 0x00ad, /soft hyphen/],
  ["tag character", 0xe0041, /tag character "A"/],
  ["cancel tag", 0xe007f, /cancel tag/],
  ["language tag", 0xe0001, /language tag/],
  ["other format character", 0x0600, /format character/],
  ["interlinear annotation", 0xfff9, /format character/],
];

/**
 * @SVCs SVC_GW_APPROVAL_0040
 */
test("every_class_of_format_character_becomes_a_named_token_in_place", () => {
  for (const [label, codePoint, name] of CLASSES) {
    const character = String.fromCodePoint(codePoint);
    const revealed = revealControls(`a${character}b`);

    expect(revealed.controls, label).toBe(1);
    expect(revealed.parts, label).toHaveLength(3);
    expect(revealed.parts[0], label).toBe("a");
    expect(revealed.parts[2], label).toBe("b");
    const token = revealed.parts[1];
    if (typeof token !== "object") throw new Error(`${label}: no token`);
    expect(token.codePoint, label).toBe(codePoint);
    expect(token.token, label).toBe(`⟨U+${hex(codePoint)}⟩`);
    expect(token.name, label).toMatch(name);
    expect(controlName(codePoint), label).toBe(token.name);
    expect(showControls(`a${character}b`), label).toBe(`a⟨U+${hex(codePoint)}⟩b`);
  }
});

/**
 * @SVCs SVC_GW_APPROVAL_0040
 */
test("a_reordered_name_reads_in_stored_order_with_every_control_named", () => {
  const revealed = revealControls("bidi-\u202Eevil\u202C-name");

  expect(revealed.controls).toBe(2);
  expect(showControls("bidi-\u202Eevil\u202C-name")).toBe("bidi-⟨U+202E⟩evil⟨U+202C⟩-name");
  expect(revealed.parts.map((part) => (typeof part === "string" ? part : part.name))).toEqual([
    "bidi-",
    "right-to-left override",
    "evil",
    "pop directional formatting",
    "-name",
  ]);
});

const UNTOUCHED: readonly [string, string][] = [
  ["ascii", "plugin-name_v2.0 (stable)"],
  ["accents", "café naïve résumé"],
  ["right-to-left letters", "שלום עולם مرحبا"],
  ["CJK", "日本語のスキル"],
  ["a plain emoji", "ship it 🚀"],
  ["a ZWJ family", "👨\u200D👩\u200D👧"],
  ["a ZWJ with a variation selector", "❤️\u200D🔥"],
  ["a ZWJ with a skin tone", "👩🏽\u200D💻"],
  ["a variation selector alone", "☺️"],
  ["an empty string", ""],
];

/**
 * @SVCs SVC_GW_APPROVAL_0040
 */
test("text_without_a_control_including_emoji_sequences_is_returned_unchanged", () => {
  for (const [label, text] of UNTOUCHED) {
    const revealed = revealControls(text);

    expect(revealed.controls, label).toBe(0);
    expect(revealed.parts, label).toEqual(text === "" ? [] : [text]);
    expect(showControls(text), label).toBe(text);
  }
});

const LONE_JOINERS: readonly [string, string][] = [
  ["between letters", "ab\u200Dcd"],
  ["after a pictograph only", "🚀\u200Dx"],
  ["before a pictograph only", "x\u200D🚀"],
  ["at the start", "\u200D🚀"],
  ["at the end", "🚀\u200D"],
];

/**
 * @SVCs SVC_GW_APPROVAL_0040
 */
test("a_zero_width_joiner_not_between_two_pictographs_is_shown", () => {
  for (const [label, text] of LONE_JOINERS) {
    expect(revealControls(text).controls, label).toBe(1);
    expect(showControls(text), label).toContain("⟨U+200D⟩");
  }
});

/**
 * @SVCs SVC_GW_APPROVAL_0040
 */
test("an_emoji_joiner_and_an_override_in_one_string_are_told_apart", () => {
  expect(showControls("👨\u200D💻\u202Ex")).toBe("👨\u200D💻⟨U+202E⟩x");
});

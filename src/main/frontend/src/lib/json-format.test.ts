import { expect, test } from "vitest";
import { formatJson } from "./json-format";

/** The document with every whitespace run outside a string removed — what formatting may change. */
function tokensOnly(text: string): string {
  let out = "";
  let inString = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (inString) {
      out += c;
      if (c === "\\") out += text[++i];
      else if (c === '"') inString = false;
    } else if (c === '"') {
      inString = true;
      out += c;
    } else if (!" \t\n\r".includes(c)) {
      out += c;
    }
  }
  return out;
}

test("a_one_line_manifest_is_indented", () => {
  expect(formatJson('{"name":"demo","plugins":[{"name":"hello","source":"./p"}],"tags":[]}')).toBe(
    [
      "{",
      '  "name": "demo",',
      '  "plugins": [',
      "    {",
      '      "name": "hello",',
      '      "source": "./p"',
      "    }",
      "  ],",
      '  "tags": []',
      "}",
    ].join("\n"),
  );
});

test("a_duplicated_key_survives_both_times_in_order", () => {
  const formatted = formatJson('{"source":"./safe","source":"https://evil.example/x"}')!;
  expect(formatted).toContain('"source": "./safe"');
  expect(formatted).toContain('"source": "https://evil.example/x"');
  expect(formatted.indexOf("./safe")).toBeLessThan(formatted.indexOf("evil"));
});

test("numbers_and_escapes_are_copied_as_written", () => {
  const formatted = formatJson('{"a":1.0,"b":12345678901234567890,"c":"\\u200b\\"x\\"","d":-0e+5}')!;
  expect(formatted).toContain('"a": 1.0');
  expect(formatted).toContain('"b": 12345678901234567890');
  expect(formatted).toContain('"c": "\\u200b\\"x\\""');
  expect(formatted).toContain('"d": -0e+5');
});

test("text_that_is_not_json_is_refused", () => {
  for (const bad of [
    '{"a":1',
    '{"a":1]',
    '{"a":tru}',
    "{'a':1}",
    '{"a":"line\nbreak"}',
    '﻿{"a":1}',
    '{"a":1}}',
    '"unterminated',
    "1,2",
  ]) {
    expect(formatJson(bad), bad).toBeNull();
  }
});

/** A small seeded generator: deterministic, so a failure reproduces. */
function generator(seed: number) {
  let state = seed;
  const next = () => ((state = (state * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
  const pick = <T,>(items: T[]) => items[Math.floor(next() * items.length)]!;
  const ws = () => pick(["", " ", "\n", "\t", "  \r\n "]);
  const scalar = () =>
    pick(['"a"', '"\\\\"', '"\\"q\\""', '"\\u00e9"', '"sp ace"', "0", "-1.50", "2e10", "true", "null"]);
  const value = (depth: number): string => {
    const kind = depth > 3 ? 0 : Math.floor(next() * 3);
    if (kind === 0) return scalar();
    const n = Math.floor(next() * 4);
    const items = Array.from({ length: n }, () =>
      kind === 1 ? ws() + value(depth + 1) + ws() : ws() + pick(['"k"', '"k"', '"x y"']) + ws() + ":" + ws() + value(depth + 1) + ws(),
    );
    return kind === 1 ? "[" + items.join(",") + "]" : "{" + items.join(",") + "}";
  };
  return () => ws() + value(0) + ws();
}

test("formatting_changes_only_the_whitespace_between_tokens", () => {
  const generate = generator(20260924);
  for (let run = 0; run < 500; run++) {
    const input = generate();
    const formatted = formatJson(input);
    expect(formatted, input).not.toBeNull();
    expect(tokensOnly(formatted!), input).toBe(tokensOnly(input));
    // Idempotent: formatting a formatted document changes nothing.
    expect(formatJson(formatted!)).toBe(formatted);
  }
});

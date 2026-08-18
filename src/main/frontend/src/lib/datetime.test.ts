import { expect, test } from "vitest";
import { ABSENT, formatDay, formatInstant, formatPrecise } from "./datetime";

test("an_instant_is_formatted_rather_than_shown_raw", () => {
  const formatted = formatInstant("2026-08-14T10:00:00Z");
  expect(formatted).not.toBe("2026-08-14T10:00:00Z");
  expect(formatted).not.toMatch(/T\d\d:\d\d/);
  expect(formatted).toMatch(/2026/);
});

test("a_day_only_value_carries_no_time_of_day", () => {
  const formatted = formatDay("2026-08-28T11:00:00Z");
  expect(formatted).toMatch(/2026/);
  expect(formatted).not.toMatch(/\d\d:\d\d/);
});

test("an_absent_value_is_the_em_dash_on_every_surface", () => {
  expect(formatInstant(undefined)).toBe(ABSENT);
  expect(formatInstant(null)).toBe(ABSENT);
  expect(formatInstant("")).toBe(ABSENT);
  expect(formatDay(undefined)).toBe(ABSENT);
});

// A timestamp the portal cannot parse is a bug worth seeing. Rendering it as an em dash
// would hide a malformed value behind the same glyph used for "there isn't one".
test("an_unparseable_value_is_shown_verbatim_not_hidden", () => {
  expect(formatInstant("not-a-date")).toBe("not-a-date");
  expect(formatDay("not-a-date")).toBe("not-a-date");
});

test("the_tooltip_keeps_the_untouched_wire_value", () => {
  expect(formatPrecise("2026-08-14T10:00:00Z")).toContain("2026-08-14T10:00:00Z");
});

import { expect, test } from "vitest";
import { ABSENT, formatDay, formatInstant, formatPrecise, formatRelative } from "./datetime";

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

test("a_relative_value_says_how_long_ago_rather_than_when", () => {
  const threeHoursAgo = new Date(Date.now() - 3 * 60 * 60 * 1000).toISOString();
  expect(formatRelative(threeHoursAgo)).toMatch(/3 hours ago/);

  // Days, not 96 hours: the largest unit that still says something is the one a reader wants.
  const fourDaysAgo = new Date(Date.now() - 4 * 24 * 60 * 60 * 1000).toISOString();
  expect(formatRelative(fourDaysAgo)).toMatch(/4 days ago/);
});

// Rounding a few seconds to "in 0 minutes" would read as the future for something that just
// happened, which is the one thing a "last used" column must not do.
test("a_value_under_a_minute_old_reads_as_now", () => {
  expect(formatRelative(new Date().toISOString())).toBe("this minute");
});

test("a_relative_value_falls_back_the_same_way_every_other_format_does", () => {
  expect(formatRelative(null)).toBe(ABSENT);
  expect(formatRelative("not-a-date")).toBe("not-a-date");
});

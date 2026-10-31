import { expect, test } from "vitest";
import type { VettingView } from "@/api/queries";
import {
  contentsHref,
  findingsByPath,
  highestSeverity,
  listedLines,
  parseLineParam,
  parseLocation,
  type FileFinding,
} from "./file-findings";

/**
 * @SVCs SVC_GW_APPROVAL_0029
 */
test("a_location_is_a_path_and_the_digits_after_its_last_colon", () => {
  expect(parseLocation("a/b.md:12")).toEqual({ path: "a/b.md", line: 12 });
  expect(parseLocation("a:b/c.sh:3")).toEqual({ path: "a:b/c.sh", line: 3 });
  expect(parseLocation("a/b.md")).toEqual({ path: "a/b.md", line: null });
  expect(parseLocation("a/b.md:x")).toEqual({ path: "a/b.md:x", line: null });
  // As WaiverScope.pathOf reads it: a colon at the start or the end is part of the path.
  expect(parseLocation("a/b.md:0")).toEqual({ path: "a/b.md", line: 0 });
  expect(parseLocation(":12")).toEqual({ path: ":12", line: null });
  expect(parseLocation("a/b.md:")).toEqual({ path: "a/b.md:", line: null });
});

const view: VettingView = {
  snapshotId: 1,
  run: {
    verdicts: [
      {
        vetter: "executable-surface",
        findings: [
          { id: "runtime-fetch-exec", severity: "high", location: "p/s.sh:2", message: "pipes a download" },
          { id: "auto-run-hook", severity: "medium", location: "p/hooks/hooks.json:4", message: "runs on Stop" },
        ],
      },
      {
        vetter: "prompt-injection",
        findings: [
          { id: "pipe-to-shell", severity: "high", location: "p/s.sh:2", message: "pipes to a shell" },
          { id: "file-not-scanned", severity: "info", location: "p/big.bin", message: "over the limit" },
        ],
      },
    ],
  },
  suppressed: [
    {
      vetter: "prompt-injection",
      ruleId: "pipe-to-shell",
      location: "p/s.sh:2",
      approvedBy: "alice",
      expiresAt: "2026-10-31T00:00:00Z",
      waiverId: 7,
    },
    // Same rule and location, another vetter: covers nothing here.
    { vetter: "secret-scan", ruleId: "runtime-fetch-exec", location: "p/s.sh:2", approvedBy: "bob" },
  ],
};

/**
 * @SVCs SVC_GW_APPROVAL_0029
 */
test("findings_are_grouped_by_path_with_their_vetter_and_waiver", () => {
  const byPath = findingsByPath(view);

  expect([...byPath.keys()].sort()).toEqual(["p/big.bin", "p/hooks/hooks.json", "p/s.sh"]);
  const script = byPath.get("p/s.sh")!;
  expect(script.map((f) => [f.vetter, f.ruleId, f.line])).toEqual([
    ["executable-surface", "runtime-fetch-exec", 2],
    ["prompt-injection", "pipe-to-shell", 2],
  ]);
  expect(script[0]!.waived).toBeNull();
  expect(script[1]!.waived).toEqual({ by: "alice", until: "2026-10-31T00:00:00Z" });
  expect(byPath.get("p/big.bin")![0]!.line).toBeNull();
  expect(findingsByPath(undefined).size).toBe(0);
  expect(findingsByPath({ snapshotId: 1 }).size).toBe(0);
});

/**
 * @SVCs SVC_GW_APPROVAL_0029
 */
test("the_highest_severity_orders_critical_high_medium_low_info", () => {
  const of = (...severities: FileFinding["severity"][]) =>
    severities.map((severity) => ({ severity }) as FileFinding);
  expect(highestSeverity(of("info", "medium", "low"))).toBe("medium");
  expect(highestSeverity(of("high", "critical"))).toBe("critical");
  expect(highestSeverity(of("low", "info"))).toBe("low");
  expect(highestSeverity([])).toBeNull();
});

/**
 * @SVCs SVC_GW_APPROVAL_0030
 */
test("a_line_parameter_is_a_positive_whole_number_or_nothing", () => {
  expect(parseLineParam("12")).toBe(12);
  for (const bad of [null, "", "0", "-3", "1.5", "12a", " 12"]) expect(parseLineParam(bad)).toBeNull();
});

/**
 * @SVCs SVC_GW_APPROVAL_0030
 */
test("a_location_becomes_the_address_of_its_file_and_line", () => {
  expect(contentsHref(3, "a b/c.md:7")).toBe("?snapshot=3&tab=contents&path=a+b%2Fc.md&line=7");
  expect(contentsHref(3, "p/big.bin")).toBe("?snapshot=3&tab=contents&path=p%2Fbig.bin");
  expect(contentsHref(3, "")).toBeNull();
});

/**
 * @SVCs SVC_GW_VETTING_0078
 */
test("a_findings_listed_lines_are_read_from_the_end_of_its_message", () => {
  // As ExecutableSurfaceVetter.lineList writes them.
  expect(listedLines("a skill script fetches a package (line 4)")).toEqual([4]);
  expect(listedLines("a code block of these instructions runs npx (lines 81, 86)")).toEqual([81, 86]);
  expect(listedLines("installs at run time (lines 2, 3, 4, 5, 6 +3 more)")).toEqual([2, 3, 4, 5, 6]);
  // A mention anywhere but the closing list, or no list at all, names no line.
  expect(listedLines("see line 3 (lines 81, 86) for more")).toEqual([]);
  expect(listedLines("declares npm dependencies (no lockfile)")).toEqual([]);
  expect(listedLines("pipes a download")).toEqual([]);

  const lines = findingsByPath({
    snapshotId: 1,
    run: {
      verdicts: [
        {
          vetter: "executable-surface",
          findings: [
            { id: "runtime-dependency", severity: "medium", location: "s/SKILL.md:81", message: "runs npx (lines 81, 86)" },
            { id: "skill-fetch-exec", severity: "high", location: "s/SKILL.md:12", message: "curl | sh (lines 40, 12)" },
            { id: "no-line", severity: "info", location: "s/SKILL.md", message: "whole file (line 7)" },
          ],
        },
      ],
    },
  })
    .get("s/SKILL.md")!
    .map((finding) => finding.lines);
  // The location's line first, then every other listed line once.
  expect(lines).toEqual([[81, 86], [12, 40], [7]]);
});

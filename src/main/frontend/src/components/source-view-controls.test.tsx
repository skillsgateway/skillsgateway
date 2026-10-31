import { render, screen } from "@testing-library/react";
import { expect, test } from "vitest";
import { SourceView } from "./source-view";

const lineOf = (container: HTMLElement, n: number) =>
  container.querySelector<HTMLElement>(`[data-line="${n}"]`)!;

/**
 * @SVCs SVC_GW_APPROVAL_0042
 */
test("a_line_with_a_control_shows_tokens_and_a_written_marker_without_a_finding", () => {
  const { container } = render(
    <SourceView
      path="p/check.js"
      text={'const level = "user";\nif (level !== "user\u202E \u2066// admin\u2069 \u2066") {\nreturn;'}
      findings={[]}
    />,
  );

  const marked = lineOf(container, 2);
  expect(marked).toHaveTextContent('if (level !== "user⟨U+202E⟩ (right-to-left override) ⟨U+2066⟩');
  expect(marked.textContent).not.toMatch(/\p{Cf}/u);
  expect(marked).toHaveAccessibleDescription(/contains invisible or bidirectional control characters/i);
  expect(marked).toHaveTextContent(/contains invisible or bidirectional control characters/i);
  expect(marked).toHaveAttribute("data-controls", "4");
  // The marker is not a finding.
  expect(screen.queryByRole("note")).not.toBeInTheDocument();
  expect(marked).not.toHaveAttribute("data-severity");

  for (const n of [1, 3]) {
    expect(lineOf(container, n)).not.toHaveAttribute("data-controls");
    expect(lineOf(container, n)).not.toHaveTextContent(/control characters/);
    expect(lineOf(container, n)).not.toHaveAccessibleDescription(/./);
  }
});

/**
 * @SVCs SVC_GW_APPROVAL_0042
 */
test("a_line_with_a_control_and_a_finding_is_described_by_both", () => {
  const { container } = render(
    <SourceView
      path="p/SKILL.md"
      text={"# Skill\nrun \u200Bthis"}
      findings={[
        {
          vetter: "prompt-injection",
          ruleId: "invisible-characters",
          severity: "high",
          message: "instruction file contains invisible or bidirectional control characters (U+200B)",
          location: "p/SKILL.md:2",
          line: 2,
          waived: null,
        },
      ]}
    />,
  );

  const marked = lineOf(container, 2);
  expect(marked).toHaveTextContent("run ⟨U+200B⟩ (zero-width space)this");
  expect(marked).toHaveAccessibleDescription(/U\+200B.*contains invisible or bidirectional control characters/i);
  expect(screen.getAllByRole("note")).toHaveLength(1);
});

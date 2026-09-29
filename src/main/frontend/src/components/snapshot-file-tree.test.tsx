import { render, screen } from "@testing-library/react";
import { expect, test } from "vitest";
import type { FileFinding } from "@/lib/file-findings";
import { SnapshotFileTree } from "./snapshot-file-tree";

const finding = (severity: FileFinding["severity"]): FileFinding => ({
  vetter: "v",
  ruleId: "r",
  severity,
  message: "m",
  location: "p/s.sh:1",
  line: 1,
  waived: null,
});

/**
 * @SVCs SVC_GW_APPROVAL_0029
 */
test("a_file_with_findings_is_marked_with_its_count_and_highest_severity", () => {
  render(
    <SnapshotFileTree
      entries={[
        { kind: "directory", path: "p", name: "p", files: 2 },
        { kind: "file", path: "p/s.sh", name: "s.sh", size: 20 },
        { kind: "file", path: "p/ok.md", name: "ok.md", size: 10 },
      ]}
      selectedPath={null}
      expanded={new Set()}
      onToggle={() => {}}
      onSelect={() => {}}
      renderChildren={() => null}
      findings={new Map([["p/s.sh", [finding("medium"), finding("high")]]])}
    />,
  );

  const marked = screen.getByRole("button", { name: "p/s.sh, 20 B, 2 findings, highest high" });
  expect(marked).toHaveTextContent("high · 2");
  expect(screen.getByRole("button", { name: "p/ok.md, 10 B" })).not.toHaveTextContent("·");
  expect(screen.getByRole("button", { name: /^p, / })).not.toHaveTextContent("high");
});

import { render, screen } from "@testing-library/react";
import { expect, test } from "vitest";
import { MarkdownView } from "./markdown-view";

test("markdown_renders_headings_lists_and_code_as_react_elements", () => {
  render(
    <MarkdownView
      text={"# Title\n\nA paragraph with `code` and **bold**.\n\n- first\n- second\n\n```\nfenced\n```\n"}
    />,
  );
  expect(screen.getByRole("heading", { level: 1, name: "Title" })).toBeInTheDocument();
  expect(screen.getByText("code")).toBeInTheDocument();
  expect(screen.getByText("bold")).toBeInTheDocument();
  expect(screen.getByRole("list")).toBeInTheDocument();
  expect(screen.getByText("fenced")).toBeInTheDocument();
});

/**
 * The inertness contract: HTML embedded in hostile markdown must appear as visible text, never
 * as markup — there is no HTML pipeline to exploit.
 */
test("embedded_html_is_shown_as_text_and_never_becomes_markup", () => {
  render(<MarkdownView text={'# Hi\n\n<img src=x onerror=alert(1)>\n\n<script>alert(2)</script>\n'} />);
  // The tags are visible as literal text...
  expect(screen.getByText(/<img src=x onerror=alert\(1\)>/)).toBeInTheDocument();
  expect(screen.getByText(/<script>alert\(2\)<\/script>/)).toBeInTheDocument();
  // ...and no image element (or anything else) was actually created from them.
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
  expect(document.querySelector("img, script")).toBeNull();
});

test("links_render_as_inert_text_with_the_target_visible_but_not_navigable", () => {
  render(<MarkdownView text={"See [docs](javascript:alert(1)) now.\n"} />);
  expect(screen.getByText("docs")).toBeInTheDocument();
  expect(screen.getByText(/javascript:alert\(1\)/)).toBeInTheDocument();
  expect(screen.queryByRole("link")).not.toBeInTheDocument();
});

import { expect, test } from "vitest";
import { labelContainsText } from "./accessible-name";

function button(html: string): Element {
  const host = document.createElement("div");
  host.innerHTML = html;
  return host.firstElementChild as Element;
}

test("a_control_without_a_label_is_named_by_its_content", () => {
  expect(labelContainsText(button("<button>Clear override</button>"))).toBe(true);
});

test("a_label_that_starts_with_the_visible_text_contains_it", () => {
  expect(labelContainsText(button('<button aria-label="Clear override (corp)">Clear override</button>'))).toBe(true);
});

test("whitespace_between_elements_and_case_do_not_matter", () => {
  expect(labelContainsText(button('<button aria-label="plugins 2 changed (3 files)"><span>plugins</span><span>2 CHANGED</span></button>'))).toBe(true);
});

test("a_label_that_rewords_the_visible_text_does_not_contain_it", () => {
  expect(labelContainsText(button('<button aria-label="Clear every chain override on corp">Clear override</button>'))).toBe(false);
});

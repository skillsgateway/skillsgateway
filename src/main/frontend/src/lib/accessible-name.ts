/**
 * Whether a control's `aria-label` contains the text it shows (WCAG 2.5.3, Label in Name; axe
 * `label-content-name-mismatch`). A control without an `aria-label` is named by its content, so it
 * holds trivially. Whitespace and case are ignored, as axe ignores them.
 */
export function labelContainsText(control: Element): boolean {
  const label = control.getAttribute("aria-label");
  if (label === null) return true;
  const squash = (text: string) => text.replace(/\s+/g, "").toLowerCase();
  return squash(label).includes(squash(control.textContent ?? ""));
}

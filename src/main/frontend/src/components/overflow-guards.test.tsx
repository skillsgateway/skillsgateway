import { render, screen } from "@testing-library/react";
import { expect, test } from "vitest";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";

// jsdom does no layout, so the class contract is what can be asserted (#543): a wide child
// must not stretch the dialog's implicit grid column past its own width.
test("dialog_content_pins_its_grid_column_to_the_dialog_width", () => {
  render(
    <Dialog open>
      <DialogContent>
        <DialogTitle>Wide</DialogTitle>
      </DialogContent>
    </Dialog>,
  );
  expect(screen.getByRole("dialog").className).toContain("grid-cols-[minmax(0,1fr)]");
});

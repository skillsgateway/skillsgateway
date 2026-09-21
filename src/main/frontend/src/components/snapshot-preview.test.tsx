import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { SnapshotPreview } from "./snapshot-preview";

function renderPreview() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <SnapshotPreview snapshotId={1} marketplace="corp-marketplace" />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

test("the_glance_leads_to_the_explorer_and_counts_what_it_did_not_show", async () => {
  renderPreview();
  const inspect = await screen.findByRole("link", { name: "Inspect contents of snapshot 1" });
  expect(inspect).toHaveAttribute("href", "/marketplaces/corp-marketplace/snapshots/1/files");
  expect(screen.getByText(/4 paths/)).toBeInTheDocument();
});

test("a_quick_open_path_links_straight_to_itself_in_the_explorer", async () => {
  renderPreview();
  const manifest = await screen.findByRole("link", { name: ".claude-plugin/marketplace.json" });
  expect(manifest).toHaveAttribute(
    "href",
    "/marketplaces/corp-marketplace/snapshots/1/files?path=.claude-plugin%2Fmarketplace.json",
  );
  const skill = screen.getByRole("link", { name: "plugins/hello/skills/hello/SKILL.md" });
  expect(skill).toHaveAttribute(
    "href",
    "/marketplaces/corp-marketplace/snapshots/1/files?path=plugins%2Fhello%2Fskills%2Fhello%2FSKILL.md",
  );
});

test("the_glance_is_a_glance_it_renders_no_file_body_of_its_own", async () => {
  renderPreview();
  await screen.findByRole("link", { name: "Inspect contents of snapshot 1" });
  // The SKILL.md used to be fetched and rendered inline here; reading a commit is the
  // explorer's job now, and this page must not quietly keep a second copy of it.
  expect(screen.queryByRole("heading", { name: "Hello skill" })).not.toBeInTheDocument();
  expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
});

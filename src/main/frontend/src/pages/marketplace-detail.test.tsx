import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { expect, test } from "vitest";
import { MarketplaceDetailPage } from "./marketplace-detail";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/marketplaces/corp-marketplace"]}>
        <Routes>
          <Route path="/marketplaces/:name" element={<MarketplaceDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

test("deleted_snapshot_shows_its_restore_deadline_and_control", async () => {
  renderPage();
  expect(await screen.findByText("deleted")).toBeInTheDocument();
  expect(screen.getByText(/restorable until 2026-08-28/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Restore snapshot 2" })).toBeInTheDocument();
  // A live snapshot offers the delete control instead.
  expect(screen.getByRole("button", { name: "Delete snapshot 1" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Delete snapshot 2" })).not.toBeInTheDocument();
});

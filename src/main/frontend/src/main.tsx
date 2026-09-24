import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router-dom";
import { AppLayout } from "@/components/app-layout";
import { AdoptionPage } from "@/pages/adoption";
import { AuditPage } from "@/pages/audit";
import {
  MarketplaceActivityPage,
  MarketplaceLayout,
  MarketplaceReviewPage,
  MarketplaceSettingsPage,
  MarketplaceSnapshotsPage,
} from "@/pages/marketplace-detail";
import { MarketplacesPage } from "@/pages/marketplaces";
import { SnapshotFilesPage } from "@/pages/snapshot-files";
import { OverviewPage } from "@/pages/overview";
import { ReviewQueuePage } from "@/pages/review-queue";
import { TokensPage } from "@/pages/tokens";
import { VettingPage } from "@/pages/vetting";
import { WebhooksPage } from "@/pages/webhooks";
import "./index.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false },
  },
});

const router = createBrowserRouter([
  {
    element: <AppLayout />,
    children: [
      { path: "/", element: <OverviewPage /> },
      { path: "/marketplaces", element: <MarketplacesPage /> },
      { path: "/review", element: <ReviewQueuePage />, handle: { layout: "full" } },
      {
        path: "/marketplaces/:name",
        element: <MarketplaceLayout />,
        handle: { layout: "full" },
        // Absolute child paths, so SpaRoutesTests reads every one of them from this file.
        children: [
          { index: true, element: <MarketplaceReviewPage /> },
          { path: "/marketplaces/:name/snapshots", element: <MarketplaceSnapshotsPage /> },
          { path: "/marketplaces/:name/activity", element: <MarketplaceActivityPage /> },
          { path: "/marketplaces/:name/settings", element: <MarketplaceSettingsPage /> },
        ],
      },
      // Wide, self-scrolling: two panes that own their scroll, in a page that does not.
      {
        path: "/marketplaces/:name/snapshots/:id/files",
        element: <SnapshotFilesPage />,
        handle: { layout: "wide" },
      },
      { path: "/audit", element: <AuditPage /> },
      { path: "/vetting", element: <VettingPage /> },
      { path: "/adoption", element: <AdoptionPage /> },
      { path: "/tokens", element: <TokensPage /> },
      { path: "/webhooks", element: <WebhooksPage /> },
    ],
  },
]);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider
      attribute="class"
      defaultTheme="system"
      enableSystem
      disableTransitionOnChange
    >
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>
  </StrictMode>,
);

import type { RouteObject } from "react-router-dom";
import { Navigate } from "react-router-dom";
import { AppLayout } from "@/components/app-layout";
import { AdoptionPage } from "@/pages/adoption";
import { AuditPage } from "@/pages/audit";
import { AuditSinksPage } from "@/pages/audit-sinks";
import {
  MarketplaceActivityPage,
  MarketplaceLayout,
  MarketplaceReviewPage,
  MarketplaceSettingsPage,
  MarketplaceSnapshotsPage,
} from "@/pages/marketplace-detail";
import { MarketplacesPage } from "@/pages/marketplaces";
import { NotFoundPage } from "@/pages/not-found";
import { SnapshotFilesPage } from "@/pages/snapshot-files";
import { OverviewPage } from "@/pages/overview";
import { ReviewQueuePage } from "@/pages/review-queue";
import { SystemTokensPage } from "@/pages/system-tokens";
import { TokensPage } from "@/pages/tokens";
import { VettingPage } from "@/pages/vetting";
import { WebhooksPage } from "@/pages/webhooks";

/**
 * The portal's client routes. Kept apart from the bootstrap so a test can render the real table,
 * and so SpaRoutesTests reads every path the server has to serve from one place.
 */
export const appRoutes: RouteObject[] = [
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
      // The personal token page lives under Security (GW_AUTH_0071); the old address keeps
      // resolving so a link or bookmark made before the move still works.
      { path: "/tokens", element: <Navigate to="/security/tokens" replace /> },
      { path: "/security", element: <Navigate to="/security/tokens" replace /> },
      { path: "/security/tokens", element: <TokensPage /> },
      { path: "/configuration/tokens", element: <SystemTokensPage /> },
      { path: "/integrations", element: <Navigate to="/integrations/webhooks" replace /> },
      { path: "/integrations/webhooks", element: <WebhooksPage /> },
      { path: "/integrations/sinks", element: <AuditSinksPage /> },
      // Moved under Integrations; the old address is published and bookmarked (GW_AUTH_0048).
      { path: "/webhooks", element: <Navigate to="/integrations/webhooks" replace /> },
      // Everything else: the gateway answers an unknown address with the portal (GW_AUTH_0067).
      { path: "*", element: <NotFoundPage />, handle: { title: "Page not found" } },
    ],
  },
];

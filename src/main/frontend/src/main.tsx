import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router-dom";
import { AppLayout } from "@/components/app-layout";
import { AuditPage } from "@/pages/audit";
import { MarketplaceDetailPage } from "@/pages/marketplace-detail";
import { MarketplacesPage } from "@/pages/marketplaces";
import { OverviewPage } from "@/pages/overview";
import { TokensPage } from "@/pages/tokens";
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
      { path: "/marketplaces/:name", element: <MarketplaceDetailPage /> },
      { path: "/audit", element: <AuditPage /> },
      { path: "/tokens", element: <TokensPage /> },
      { path: "/webhooks", element: <WebhooksPage /> },
    ],
  },
]);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider attribute="class" disableTransitionOnChange>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>
  </StrictMode>,
);

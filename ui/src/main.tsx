import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, Navigate, RouterProvider } from "react-router-dom";
import { AppLayout } from "@/components/app-layout";
import { AuditPage } from "@/pages/audit";
import { MarketplacesPage } from "@/pages/marketplaces";
import { TokensPage } from "@/pages/tokens";
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
      { path: "/", element: <Navigate to="/marketplaces" replace /> },
      { path: "/marketplaces", element: <MarketplacesPage /> },
      { path: "/audit", element: <AuditPage /> },
      { path: "/tokens", element: <TokensPage /> },
    ],
  },
]);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);

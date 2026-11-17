import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { ThemeProvider } from "next-themes";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { expect, test } from "vitest";
import { server } from "@/test/msw-server";
import { appRoutes } from "./routes";

function open(path: string, roles: { role: string; source: string }[] = []) {
  server.use(
    http.get("/api/v1/me", () => HttpResponse.json({ username: "bob", roles, claimsTruncated: false })),
  );
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <ThemeProvider attribute="class">
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );
  return router;
}

/**
 * Personal tokens have one home, reached from the user menu's Security entry; the old address
 * resolves to it so links and bookmarks keep working.
 *
 * @SVCs SVC_GW_AUTH_0086
 */
test.each(["/tokens", "/security"])("%s_resolves_to_the_personal_access_tokens_page", async (path) => {
  const router = open(path);
  expect(await screen.findByRole("heading", { name: "Personal access tokens" })).toBeInTheDocument();
  expect(router.state.location.pathname).toBe("/security/tokens");
});

/** @SVCs SVC_GW_AUTH_0087 */
test("a_session_with_no_role_gets_the_statement_at_the_system_tokens_address", async () => {
  const router = open("/configuration/tokens");
  expect(await screen.findByRole("alert")).toHaveTextContent("This page needs the administrative role.");
  expect(router.state.location.pathname).toBe("/configuration/tokens");
});

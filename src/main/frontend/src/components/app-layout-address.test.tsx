import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider } from "next-themes";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { expect, test } from "vitest";
import { NotFoundPage } from "@/pages/not-found";
import { AppLayout } from "./app-layout";

/** The shell over a few child routes, shaped as main.tsx declares them (catch-all with its title). */
function renderShell(initialEntry: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      {
        element: <AppLayout />,
        children: [
          { path: "/", element: <p>overview</p> },
          { path: "/audit", element: <p>audit</p> },
          { path: "/marketplaces/:name", element: <p>review</p> },
          { path: "/marketplaces/:name/snapshots", element: <p>snapshots</p> },
          { path: "*", element: <NotFoundPage />, handle: { title: "Page not found" } },
        ],
      },
    ],
    { initialEntries: [initialEntry] },
  );
  render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
        <RouterProvider router={router} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
  return router;
}

/**
 * The tab names the page, and the marketplace for a marketplace's section, ahead of the product;
 * the sign-in marker leaves the address and every other parameter stays.
 *
 * @SVCs SVC_GW_AUTH_0082
 */
test("each_page_names_itself_in_the_tab_and_the_sign_in_marker_leaves_the_address", async () => {
  renderShell("/audit");
  await waitFor(() => expect(document.title).toBe("Audit log · Skills Gateway"));

  renderShell("/marketplaces/acme/snapshots");
  await waitFor(() => expect(document.title).toBe("Snapshots · acme · Skills Gateway"));

  const router = renderShell("/marketplaces/acme?snapshot=7&continue&tab=diff");
  await waitFor(() => expect(router.state.location.search).toBe("?snapshot=7&tab=diff"));
  expect(router.state.location.pathname).toBe("/marketplaces/acme");
  expect(document.title).toBe("Review · acme · Skills Gateway");
});

/**
 * An address the portal does not have is said to be one, inside the shell, with a way back.
 *
 * @SVCs SVC_GW_AUTH_0080
 */
test("an_unknown_address_is_page_not_found_inside_the_shell", async () => {
  renderShell("/nonexistent/route");
  expect(await screen.findByRole("heading", { name: "Page not found" })).toBeInTheDocument();
  expect(screen.getByRole("navigation", { name: "Main" })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Go to the overview" })).toHaveAttribute("href", "/");
  await waitFor(() => expect(document.title).toBe("Page not found · Skills Gateway"));
});

/** An address whose escapes do not decode still gets a title rather than taking the shell down. */
test("an_address_that_does_not_decode_still_names_its_tab", async () => {
  renderShell("/marketplaces/%E0%A4%A/snapshots");
  expect(await screen.findByText("snapshots")).toBeInTheDocument();
  await waitFor(() => expect(document.title).toBe("Snapshots · %E0%A4%A · Skills Gateway"));
});

/** The first Tab stop skips the navigation and lands on the page itself. */
test("the_first_tab_stop_skips_to_the_main_content", async () => {
  const user = userEvent.setup();
  renderShell("/audit");
  await screen.findByText("audit");
  await user.tab();
  const skip = screen.getByRole("link", { name: "Skip to content" });
  expect(skip).toHaveFocus();
  expect(skip).toHaveAttribute("href", "#main");
  expect(screen.getByRole("main")).toHaveAttribute("id", "main");
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider } from "next-themes";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, expect, test } from "vitest";
import { AppLayout } from "./app-layout";

function renderLayout() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
        <MemoryRouter>
          <AppLayout />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
});

/**
 * The theme control is three-state: it defaults to "system" (follow the OS) and cycles
 * system → light → dark → system, persisting the choice. The accessible name announces the
 * current state and the next one so the icon-only control is not opaque.
 */
test("theme_toggle_cycles_system_light_dark", async () => {
  const user = userEvent.setup();
  renderLayout();

  // Defaults to system.
  const toggle = await screen.findByRole("button", { name: /Theme: system/ });

  await user.click(toggle);
  expect(screen.getByRole("button", { name: /Theme: light/ })).toBeInTheDocument();
  expect(window.localStorage.getItem("theme")).toBe("light");

  await user.click(screen.getByRole("button", { name: /Theme: light/ }));
  expect(screen.getByRole("button", { name: /Theme: dark/ })).toBeInTheDocument();
  expect(window.localStorage.getItem("theme")).toBe("dark");

  await user.click(screen.getByRole("button", { name: /Theme: dark/ }));
  expect(screen.getByRole("button", { name: /Theme: system/ })).toBeInTheDocument();
  expect(window.localStorage.getItem("theme")).toBe("system");
});

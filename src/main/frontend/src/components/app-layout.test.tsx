import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider } from "next-themes";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, expect, test, vi } from "vitest";
import { AppLayout, UserMenuView } from "./app-layout";

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

function renderMenu(props: Parameters<typeof UserMenuView>[0]) {
  return render(
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
      <MemoryRouter>
        <UserMenuView {...props} />
      </MemoryRouter>
    </ThemeProvider>,
  );
}

/** Opens the signed-in user's menu, which every control below now lives inside. */
async function openMenu(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: /Signed in as/ }));
}

beforeEach(() => {
  window.localStorage.clear();
});

/**
 * The theme control is three-state: it defaults to "system" (follow the OS) and cycles
 * system → light → dark → system, persisting the choice. The accessible name announces the
 * current state and the next one so the control is not opaque. It lives in the user menu and
 * keeps the menu open, so the states can be tried in place.
 */
test("theme_toggle_cycles_system_light_dark", async () => {
  const user = userEvent.setup();
  renderLayout();
  await openMenu(user);

  // Defaults to system.
  const toggle = await screen.findByRole("menuitem", { name: /Theme: system/ });

  await user.click(toggle);
  expect(screen.getByRole("menuitem", { name: /Theme: light/ })).toBeInTheDocument();
  expect(window.localStorage.getItem("theme")).toBe("light");

  await user.click(screen.getByRole("menuitem", { name: /Theme: light/ }));
  expect(screen.getByRole("menuitem", { name: /Theme: dark/ })).toBeInTheDocument();
  expect(window.localStorage.getItem("theme")).toBe("dark");

  await user.click(screen.getByRole("menuitem", { name: /Theme: dark/ }));
  expect(screen.getByRole("menuitem", { name: /Theme: system/ })).toBeInTheDocument();
  expect(window.localStorage.getItem("theme")).toBe("system");
});

/**
 * Tokens are a per-user surface: the entry point is the user menu, and the estate-wide
 * navigation does not carry one.
 */
test("tokens_are_reached_from_the_user_menu_and_not_from_the_sidebar", async () => {
  const user = userEvent.setup();
  renderLayout();

  expect(
    screen.queryByRole("link", { name: "Access tokens" }),
  ).not.toBeInTheDocument();

  await openMenu(user);
  expect(await screen.findByRole("menuitem", { name: "Your tokens" })).toBeInTheDocument();
});

/** Every role is named with the marketplace it is scoped to and where it came from. */
test("roles_are_listed_with_their_source", async () => {
  const user = userEvent.setup();
  renderMenu({
    me: {
      username: "alice",
      roles: [
        { role: "admin", marketplace: undefined, source: "config" },
        { role: "approver", marketplace: "corp-marketplace", source: "grant" },
        { role: "auditor", marketplace: undefined, source: "claim" },
      ],
      claimsTruncated: false,
    },
  });
  await openMenu(user);

  expect(await screen.findByText("admin — from configuration")).toBeInTheDocument();
  expect(screen.getByText("approver of corp-marketplace — granted in the portal")).toBeInTheDocument();
  expect(screen.getByText("auditor — from your identity provider")).toBeInTheDocument();

  // Arrow-key navigation in a menu visits only items, so the roles reach a screen reader
  // through the popup's own name.
  expect(screen.getByRole("button", { name: /Signed in as alice/ })).toHaveAccessibleName(
    "Signed in as alice. Roles: admin — from configuration, approver of corp-marketplace — granted in the portal, auditor — from your identity provider.",
  );
});

/** A session with no role is told so, and told what it can still do. */
test("a_session_with_no_role_is_told_what_it_can_still_do", async () => {
  const user = userEvent.setup();
  renderMenu({ me: { username: "nobody", roles: [], claimsTruncated: false } });
  await openMenu(user);

  expect(await screen.findByText(/No role\./)).toBeInTheDocument();
  expect(screen.getByRole("menuitem", { name: "Your tokens" })).toBeInTheDocument();
});

/** A truncated membership claim makes the list incomplete, and the menu says so. */
test("a_truncated_membership_claim_is_stated", async () => {
  const user = userEvent.setup();
  renderMenu({
    me: {
      username: "alice",
      roles: [{ role: "auditor", marketplace: undefined, source: "claim" }],
      claimsTruncated: true,
    },
  });
  await openMenu(user);

  expect(await screen.findByText(/may be incomplete/)).toBeInTheDocument();
});

/** Sign out ends the session; the control disables itself while it is doing so. */
test("sign_out_ends_the_session", async () => {
  const user = userEvent.setup();
  const onSignOut = vi.fn();
  renderMenu({
    me: { username: "alice", roles: [], claimsTruncated: false },
    onSignOut,
  });
  await openMenu(user);

  await user.click(await screen.findByRole("menuitem", { name: "Sign out" }));
  expect(onSignOut).toHaveBeenCalledOnce();
});

/** A sign-out that failed leaves the session live, so the control comes back. */
test("a_failed_sign_out_restores_the_control", async () => {
  const user = userEvent.setup();
  renderMenu({
    me: { username: "alice", roles: [], claimsTruncated: false },
    onSignOut: () => Promise.reject(new Error("Could not reach the gateway")),
  });
  await openMenu(user);

  await user.click(await screen.findByRole("menuitem", { name: "Sign out" }));
  expect(await screen.findByRole("menuitem", { name: "Sign out" })).toBeEnabled();
});

/**
 * Under the development escape hatch the principal is invented per request, so there is no
 * session to end: the menu explains that instead of offering a control that cannot work.
 */
test("the_development_escape_hatch_offers_no_sign_out", async () => {
  const user = userEvent.setup();
  renderMenu({
    me: {
      username: "dev",
      roles: [{ role: "admin", marketplace: undefined, source: "dev-insecure-auth" }],
      claimsTruncated: false,
    },
  });
  await openMenu(user);

  expect(await screen.findByText(/no session to end/)).toBeInTheDocument();
  expect(screen.queryByRole("menuitem", { name: "Sign out" })).not.toBeInTheDocument();
  expect(screen.getByText("admin — development escape hatch")).toBeInTheDocument();
});

/** A session that cannot be read is an error, not an empty header. */
test("a_session_that_cannot_be_read_is_an_error", () => {
  renderMenu({ isError: true });
  expect(screen.getByRole("alert")).toHaveTextContent("Could not read your session");
});

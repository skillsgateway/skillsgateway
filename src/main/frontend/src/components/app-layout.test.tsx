import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider } from "next-themes";
import { createMemoryRouter, MemoryRouter, RouterProvider } from "react-router-dom";
import { beforeEach, expect, test, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw-server";
import { AppLayout, UserMenuView } from "./app-layout";

// A data router, as main.tsx uses: the shell reads each route's `handle` to decide whether the
// page gets the reading column or the whole viewport, and only a data router carries one.
function renderLayout(initialEntry = "/") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: "*", element: <AppLayout /> }], {
    initialEntries: [initialEntry],
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
        <RouterProvider router={router} />
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

/**
 * The manual and the source are one click from any page. Both leave the portal, and the icon
 * saying so is decoration — the accessible name has to carry it (GW_INGEST_0007).
 */
test("the_sidebar_links_out_to_the_manual_and_the_source", async () => {
  renderLayout();

  for (const name of ["Documentation, opens in a new tab", "Source code, opens in a new tab"]) {
    const link = await screen.findByRole("link", { name });
    expect(link).toHaveAttribute("target", "_blank");
    expect(link.getAttribute("rel")).toContain("noopener");
  }
  expect(screen.getByRole("link", { name: "Documentation, opens in a new tab" })).toHaveAttribute(
    "href",
    "https://skillsgateway.github.io/skillsgateway/",
  );
  expect(screen.getByRole("link", { name: "Source code, opens in a new tab" })).toHaveAttribute(
    "href",
    "https://github.com/skillsgateway/skillsgateway",
  );
});

/**
 * The shell describing itself: the group of reference links says what it is, and the footer says
 * which build is answering. Both were wrong or missing (#466).
 */
test("the_reference_group_is_named_for_what_it_holds_not_called_tools", async () => {
  renderLayout();
  await screen.findByRole("navigation", { name: "Main" });

  expect(screen.getByText("Reference")).toBeInTheDocument();
  // Nothing under it does anything to the gateway, so "Tools" was a promise the group did not keep.
  expect(screen.queryByText("Tools")).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: "API reference" })).toHaveAttribute("href", "/docs");
});

test("the_sidebar_states_the_build_that_is_answering", async () => {
  renderLayout();

  const version = await screen.findByText("0.3.0");
  // Scoped to the footer: the brand mark at the top of the sidebar says the product name too.
  expect(version.parentElement).toHaveTextContent("Skills Gateway 0.3.0");
});

test("a_build_that_reports_no_version_renders_no_footer_rather_than_the_word_unknown", async () => {
  server.use(
    http.get("/api/v1/me", () =>
      HttpResponse.json({ username: "alice", roles: [], claimsTruncated: false }),
    ),
  );
  renderLayout();
  await screen.findByRole("navigation", { name: "Main" });

  // Silence is the honest rendering of "I do not know"; "unknown" reads like a shipped version.
  expect(screen.queryByText(/unknown/i)).not.toBeInTheDocument();
  expect(screen.queryByText("0.3.0")).not.toBeInTheDocument();
});

/**
 * The navigation states what awaits a decision across marketplaces on every page, and inside a
 * marketplace it lists that marketplace's sections with its own count. The fixture marketplace
 * has two decidable snapshots (one held, one revoked).
 *
 * @SVCs SVC_GW_INGEST_0037
 */
test("the_sidebar_counts_what_awaits_a_decision_and_opens_a_marketplace_into_its_sections", async () => {
  const outside = renderLayout("/audit");
  const main = screen.getByRole("navigation", { name: "Main" });
  expect(
    await within(main).findByRole("link", { name: /Review queue, 2 awaiting a decision across marketplaces/ }),
  ).toHaveAttribute("href", "/review");
  // Outside a marketplace no sections are listed.
  expect(within(main).queryByRole("list", { name: /sections/ })).not.toBeInTheDocument();
  outside.unmount();

  renderLayout("/marketplaces/corp-marketplace/settings");
  const sections = await screen.findByRole("list", { name: "corp-marketplace sections" });
  expect(await within(sections).findByRole("link", { name: /Review, 2 awaiting a decision/ })).toHaveAttribute(
    "href",
    "/marketplaces/corp-marketplace",
  );
  // Exactly one entry is current: the section, not the Marketplaces list above it.
  expect(within(sections).getByRole("link", { name: "Settings" })).toHaveAttribute("aria-current", "page");
  expect(
    within(screen.getByRole("navigation", { name: "Main" })).getByRole("link", { name: "Marketplaces" }),
  ).not.toHaveAttribute("aria-current");
  expect(screen.getByText("corp-marketplace · Settings")).toBeInTheDocument();
});

test("a_quiet_queue_shows_no_count", async () => {
  server.use(http.get("/api/v1/marketplaces", () => HttpResponse.json([])));
  renderLayout("/");
  const link = await screen.findByRole("link", { name: "Review queue" });
  expect(link).not.toHaveTextContent(/\d/);
});

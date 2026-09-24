import {
  ChevronDown,
  Home,
  Inbox,
  KeyRound,
  LogOut,
  Monitor,
  Moon,
  ScrollText,
  ShieldCheck,
  Store,
  Sun,
  TrendingUp,
  UserRound,
  Webhook,
  BookOpen,
  BookMarked,
  Code2,
  ExternalLink,
} from "lucide-react";
import { useTheme } from "next-themes";
import { useEffect, useState } from "react";
import { NavLink, Outlet, useLocation, useMatch, useMatches, useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Toaster } from "@/components/ui/sonner";
import { cn } from "@/lib/utils";
import { signOut } from "@/api/client";
import { useAwaitingDecision, useIsAdmin, useMe, type EffectiveRole, type MeView } from "@/api/queries";
import { isDecidable } from "@/lib/snapshot-roles";

type NavItem = {
  to: string;
  label: string;
  icon: typeof Home;
  /** Matched exactly rather than by prefix — only the overview needs it. */
  end?: boolean;
  /** Offered only to a session holding the administrative role. */
  adminOnly?: boolean;
  /** Carries the count of snapshots awaiting a decision across marketplaces. */
  awaiting?: boolean;
};

const groups: { label: string; items: NavItem[] }[] = [
  {
    label: "Gateway",
    items: [
      { to: "/", label: "Overview", icon: Home, end: true },
      { to: "/review", label: "Review queue", icon: Inbox, awaiting: true },
      // Exact: inside a marketplace, its section is the active entry, not the list above it.
      { to: "/marketplaces", label: "Marketplaces", icon: Store, end: true },
    ],
  },
  {
    label: "Governance",
    items: [
      { to: "/audit", label: "Audit log", icon: ScrollText },
      // Admin-only: the chain settings decide how much evidence stands behind every approval, so
      // neither they nor their values are shown to approvers or auditors. Hiding the entry is a
      // courtesy — the server refuses every read behind it independently.
      { to: "/vetting", label: "Vetting", icon: ShieldCheck, adminOnly: true },
      { to: "/adoption", label: "Adoption", icon: TrendingUp },
      { to: "/webhooks", label: "Webhooks", icon: Webhook },
    ],
  },
];

// Kept in step with mkdocs.yml's `site_url` and `repo_url`; deliberately not configurable
// (see the portal-links-out design).
const DOCS_URL = "https://skillsgateway.github.io/skillsgateway/";
const REPO_URL = "https://github.com/skillsgateway/skillsgateway";

const toolLinkClass =
  "flex items-center gap-2.5 rounded-md px-2.5 py-1.5 text-sm font-medium text-sidebar-foreground hover:bg-sidebar-accent";

/** A Reference entry that leaves the portal. The icon is decoration, so the name carries the warning. */
function OutboundLink({ href, label, icon: Icon }: { href: string; label: string; icon: typeof Home }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      aria-label={`${label}, opens in a new tab`}
      className={toolLinkClass}
    >
      <Icon className="size-4" aria-hidden />
      {label}
      <ExternalLink className="ml-auto size-3 text-muted-foreground" aria-hidden />
    </a>
  );
}

const SECTION_LABEL: Record<string, string> = {
  "": "Review",
  snapshots: "Snapshots",
  activity: "Activity",
  settings: "Settings",
};

const navItemClass = ({ isActive }: { isActive: boolean }) =>
  cn(
    "flex items-center gap-2.5 rounded-md px-2.5 py-1.5 text-sm font-medium text-sidebar-foreground hover:bg-sidebar-accent",
    isActive && "bg-sidebar-primary text-sidebar-primary-foreground hover:bg-sidebar-primary",
  );

/** A count beside a nav entry; absent at zero, so a quiet sidebar means a quiet queue. */
function NavCount({ count, label }: { count: number; label: string }) {
  if (count === 0) return null;
  return (
    <>
      <span
        aria-hidden
        className="ml-auto rounded-md border bg-muted px-1.5 font-mono text-[11px] text-foreground"
      >
        {count}
      </span>
      <span className="sr-only">, {label}</span>
    </>
  );
}

/**
 * The open marketplace's sections, nested beneath Marketplaces while the address is inside one.
 * Derived from the route, never stored: leaving the marketplace is what closes it.
 */
export function MarketplaceSections({ name, awaiting }: { name: string; awaiting: number }) {
  const base = `/marketplaces/${encodeURIComponent(name)}`;
  const sections = [
    { to: base, label: "Review", end: true },
    { to: `${base}/snapshots`, label: "Snapshots" },
    { to: `${base}/activity`, label: "Activity" },
    { to: `${base}/settings`, label: "Settings" },
  ];
  return (
    <div className="mt-0.5 ml-4 border-l pl-2">
      <div className="truncate px-2.5 py-1 text-xs font-medium text-muted-foreground" title={name}>
        {name}
      </div>
      <ul aria-label={`${name} sections`} className="space-y-0.5">
        {sections.map((section) => (
          <li key={section.to}>
            <NavLink to={section.to} end={section.end} className={navItemClass}>
              {section.label}
              {section.label === "Review" ? (
                <NavCount count={awaiting} label={`${awaiting} awaiting a decision`} />
              ) : null}
            </NavLink>
          </li>
        ))}
      </ul>
    </div>
  );
}

function breadcrumb(pathname: string): string {
  if (pathname === "/") return "Overview";
  if (/^\/marketplaces\/[^/]+\/snapshots\//.test(pathname)) return "Snapshot contents";
  const section = /^\/marketplaces\/([^/]+)(?:\/([^/]+))?/.exec(pathname);
  if (section) return `${decodeURIComponent(section[1]!)} · ${SECTION_LABEL[section[2] ?? ""] ?? "Review"}`;
  if (pathname.startsWith("/review")) return "Review queue";
  if (pathname.startsWith("/marketplaces")) return "Marketplaces";
  if (pathname.startsWith("/audit")) return "Audit log";
  if (pathname.startsWith("/vetting")) return "Vetting";
  if (pathname.startsWith("/adoption")) return "Adoption";
  if (pathname.startsWith("/tokens")) return "Access tokens";
  if (pathname.startsWith("/webhooks")) return "Webhooks";
  return "";
}

/**
 * The airlock brand mark (brand/mark.svg): two gates in series over one road, a
 * skill entering hollow (quarantined) and leaving filled (approved and served).
 * Strokes inherit currentColor; the approved node is the accent token.
 */
function BrandMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 48 48"
      fill="none"
      stroke="currentColor"
      strokeWidth="4"
      strokeLinecap="round"
      className={className}
      aria-hidden
    >
      <path d="M16 6v11M16 31v11" />
      {/* The approving gate carries the accent, like the served node it hands off to. */}
      <path d="M32 6v11M32 31v11" className="stroke-primary" />
      <circle cx="6" cy="24" r="3.5" strokeWidth="3.5" />
      <path d="M11.5 24h24.5" />
      <circle cx="42" cy="24" r="5" className="fill-primary" stroke="none" />
    </svg>
  );
}

/** Cycle order for the theme control: system (follow the OS) → light → dark → system. */
const THEME_CYCLE = ["system", "light", "dark"] as const;
const THEME_ICON = { system: Monitor, light: Sun, dark: Moon } as const;

/**
 * The source values `GET /api/me` reports, said the way the person reading them would.
 * "dev-insecure-auth" in particular means nothing to a reader who has not read the
 * configuration reference, and an unmapped value renders verbatim rather than being
 * dropped — omitting a role silently would misstate what the session holds.
 */
const ROLE_SOURCE: Record<string, string> = {
  config: "from configuration",
  grant: "granted in the portal",
  claim: "from your identity provider",
  "dev-insecure-auth": "development escape hatch",
};

/** The escape hatch is recognisable from the roles it confers; nothing else reports it. */
const DEV_INSECURE_AUTH = "dev-insecure-auth";

function roleLine(role: EffectiveRole): string {
  const scope = role.marketplace ? `${role.role} of ${role.marketplace}` : (role.role ?? "");
  return `${scope} — ${ROLE_SOURCE[role.source ?? ""] ?? role.source}`;
}

/**
 * Three-state theme control, inside the user menu: it cycles system → light → dark,
 * defaulting to system (follow the OS), and names the *chosen* state — "system" while
 * following the OS, not the resolved light or dark. next-themes persists the choice in
 * localStorage. The item keeps the menu open, so the three states can be tried in place.
 */
function ThemeItem() {
  const { theme, setTheme } = useTheme();
  // next-themes only knows the stored theme after mount; render the default until then so the
  // label never flips on first paint.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  const current = (mounted && theme && (THEME_CYCLE as readonly string[]).includes(theme)
    ? theme
    : "system") as (typeof THEME_CYCLE)[number];
  const next = THEME_CYCLE[(THEME_CYCLE.indexOf(current) + 1) % THEME_CYCLE.length]!;
  const Icon = THEME_ICON[current];
  return (
    <DropdownMenuItem
      closeOnClick={false}
      aria-label={`Theme: ${current}. Switch to ${next} theme.`}
      onClick={() => setTheme(next)}
    >
      <Icon className="size-4" aria-hidden />
      <span>Theme: {current}</span>
    </DropdownMenuItem>
  );
}

/**
 * The trigger's accessible name: who is signed in, and what they hold. The roles are text
 * inside a menu, and arrow-key navigation there visits only items — so a screen reader
 * would never reach them. Naming the trigger with them is what makes them audible.
 */
function summary(me: MeView): string {
  const roles = me.roles ?? [];
  const held = roles.length === 0 ? "no role" : roles.map(roleLine).join(", ");
  const truncated = me.claimsTruncated ? "; the role list may be incomplete" : "";
  return `Signed in as ${me.username ?? ""}. Roles: ${held}${truncated}.`;
}

/** The roles block: one line per effective role, or the no-role case said in words. */
function RolesSection({ me }: { me: MeView }) {
  const roles = me.roles ?? [];
  return (
    <DropdownMenuGroup>
      <DropdownMenuLabel>Roles</DropdownMenuLabel>
      {roles.length === 0 ? (
        <p className="px-1.5 pb-1 text-xs text-muted-foreground">
          No role. You can read the portal and manage your own access tokens.
        </p>
      ) : (
        // A list element would be a child `menu` does not allow (axe: aria-required-children),
        // so each role is a line of text inside the group rather than a `ul`.
        roles.map((role) => (
          <p key={roleLine(role)} className="px-1.5 pb-1 text-xs text-foreground">
            {roleLine(role)}
          </p>
        ))
      )}
      {me.claimsTruncated ? (
        <p className="px-1.5 pb-1 text-xs text-muted-foreground">
          Your identity provider truncated the membership claim, so this list may be incomplete.
        </p>
      ) : null}
    </DropdownMenuGroup>
  );
}

/**
 * The signed-in user's menu: who you are, what you are and where each role came from, the
 * theme control, your own tokens, and the way out. It reports what the session holds; it
 * never gates on it — the server is the authority and refuses what it must.
 *
 * Presentational, so every persona it has to survive — roles from each source, no role at
 * all, a truncated claim, the development escape hatch, and a session that could not be
 * read — is a story rather than a fixture nobody can reach.
 *
 * @Requirements GW_AUTH_0044, GW_AUTH_0045, GW_AUTH_0046
 */
export function UserMenuView({
  me,
  isError = false,
  onTokens,
  onSignOut = signOut,
}: {
  me?: MeView;
  isError?: boolean;
  onTokens?: () => void;
  onSignOut?: () => void | Promise<void>;
}) {
  const [signingOut, setSigningOut] = useState(false);

  if (isError) {
    return (
      <p role="alert" className="text-sm text-destructive">
        Could not read your session
      </p>
    );
  }
  if (!me) {
    return <p className="text-sm text-muted-foreground">Loading…</p>;
  }

  const username = me.username ?? "";
  // Under skills-gateway.dev-insecure-auth the principal is invented per request, so there is
  // no session to end and a sign-out control would only appear to fail.
  const devInsecureAuth = (me.roles ?? []).some((role) => role.source === DEV_INSECURE_AUTH);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button variant="outline" className="gap-2" aria-label={summary(me)}>
            <UserRound className="size-4" aria-hidden />
            <span className="max-w-40 truncate">{username}</span>
            <ChevronDown className="size-4 text-muted-foreground" aria-hidden />
          </Button>
        }
      />
      <DropdownMenuContent align="end" className="w-80">
        {/* Groups, not bare children: the popup is a `menu`, whose own children have to be
            menu items or groups. Everything that is not an item lives inside one. */}
        <DropdownMenuGroup>
          <DropdownMenuLabel>Signed in as</DropdownMenuLabel>
          <p className="px-1.5 pb-1 text-sm font-medium break-all">{username}</p>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <RolesSection me={me} />
        <DropdownMenuSeparator />
        <ThemeItem />
        <DropdownMenuItem onClick={onTokens}>
          <KeyRound className="size-4" aria-hidden />
          <span>Your tokens</span>
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        {devInsecureAuth ? (
          <DropdownMenuGroup>
            <p className="px-1.5 py-1 text-xs text-muted-foreground">
              The development escape hatch is on — there is no session to end.
            </p>
          </DropdownMenuGroup>
        ) : (
          <DropdownMenuItem
            // The menu stays open while the request is in flight: the label says what is
            // happening, and a failure puts the control back where the reader is looking.
            closeOnClick={false}
            disabled={signingOut}
            onClick={() => {
              setSigningOut(true);
              // A failed sign-out must not look like a finished one: the session is still
              // live, so the control comes back rather than the browser moving on.
              void Promise.resolve(onSignOut()).catch((error: Error) => {
                setSigningOut(false);
                toast.error(error.message || "Could not sign out");
              });
            }}
          >
            <LogOut className="size-4" aria-hidden />
            <span>{signingOut ? "Signing out…" : "Sign out"}</span>
          </DropdownMenuItem>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** The shell's instance of the menu: the session read, and routing for "Your tokens". */
function UserMenu() {
  const me = useMe();
  const navigate = useNavigate();
  return (
    <UserMenuView me={me.data} isError={me.isError} onTokens={() => void navigate("/tokens")} />
  );
}

/**
 * Which build is answering (GW_AUTH_0047).
 *
 * The first question of any incident, and until now it could not be answered without leaving the
 * portal for a shell. It rides on `me`, which the shell already fetches once per load, so this
 * costs no request.
 *
 * Renders nothing at all when the gateway reports no version — an exploded build carries no build
 * information, and the honest rendering of "I do not know" is silence rather than the word
 * "unknown", which reads like a version somebody shipped.
 */
function GatewayVersion() {
  const me = useMe();
  const version = me.data?.version;
  if (!version) return null;
  return (
    <div className="border-t px-4 py-3">
      <span className="text-[11px] text-muted-foreground">
        Skills Gateway <span className="font-mono">{version}</span>
      </span>
    </div>
  );
}

/**
 * Portal shell: grouped sidebar navigation, breadcrumb top bar, and the signed-in user's
 * menu — identity, roles, theme, personal tokens and sign out.
 *
 * @Requirements GW_INGEST_0007, GW_AUTH_0044
 */
export function AppLayout() {
  const location = useLocation();
  // A route asks for the whole viewport by declaring it, rather than the shell recognising
  // paths: a workspace whose panes own their scroll cannot live inside the reading column.
  // Only from lg up — below that there is no room for two panes side by side, so the page
  // scrolls like every other one rather than clipping itself into a phone-sized viewport.
  const layouts = useMatches().map(
    (match) => (match.handle as { layout?: string } | undefined)?.layout,
  );
  const wide = layouts.includes("wide");
  // "full": the page scrolls like any other but uses the width — the review surfaces, where a
  // diff or a file needs it. Capped so an ultrawide monitor does not pull a table's columns apart.
  const full = !wide && layouts.includes("full");
  // A hint only: the administrator-only pages are protected by the server refusing their reads,
  // not by the sidebar declining to mention them.
  const isAdmin = useIsAdmin();
  const queue = useAwaitingDecision();
  const awaitingTotal = queue.rows.length;
  const marketplaceMatch = useMatch("/marketplaces/:name/*");
  const openMarketplace = marketplaceMatch?.params.name;
  return (
    <div
      className={cn(
        "flex bg-background text-foreground",
        wide ? "min-h-screen lg:h-screen lg:overflow-hidden" : "min-h-screen",
      )}
    >
      <aside className="flex w-60 shrink-0 flex-col border-r bg-sidebar">
        <div className="flex items-center gap-2 px-4 py-4 font-semibold">
          <BrandMark className="size-5" />
          Skills Gateway
        </div>
        <nav aria-label="Main" className="flex-1 space-y-5 px-3 py-2">
          {groups.map((group) => (
            <div key={group.label}>
              <div className="px-2 pb-1 text-[11px] font-semibold tracking-wider text-muted-foreground uppercase">
                {group.label}
              </div>
              <ul className="space-y-0.5">
                {group.items
                  .filter((item) => item.adminOnly !== true || isAdmin)
                  .map(({ to, label, icon: Icon, end, awaiting }) => (
                  <li key={to}>
                    <NavLink to={to} end={end} className={navItemClass}>
                      <Icon className="size-4" aria-hidden />
                      {label}
                      {awaiting ? (
                        <NavCount
                          count={awaitingTotal}
                          label={`${awaitingTotal} awaiting a decision across marketplaces`}
                        />
                      ) : null}
                    </NavLink>
                    {to === "/marketplaces" && openMarketplace ? (
                      <MarketplaceSections
                        name={openMarketplace}
                        awaiting={
                          (queue.data?.find((m) => m.name === openMarketplace)?.snapshots ?? []).filter(
                            isDecidable,
                          ).length
                        }
                      />
                    ) : null}
                  </li>
                ))}
              </ul>
            </div>
          ))}
          <div>
            {/* Reference, not "Tools": nothing in this group does anything to the gateway. Two
                leave the portal and the third is the same-origin API reference page. */}
            <div className="px-2 pb-1 text-[11px] font-semibold tracking-wider text-muted-foreground uppercase">
              Reference
            </div>
            <a href="/docs" className={toolLinkClass}>
              <BookOpen className="size-4" aria-hidden />
              API reference
            </a>
            <OutboundLink href={DOCS_URL} label="Documentation" icon={BookMarked} />
            <OutboundLink href={REPO_URL} label="Source code" icon={Code2} />
          </div>
        </nav>
        <GatewayVersion />
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between gap-4 border-b px-6 py-3">
          <div className="text-xs font-semibold tracking-wider text-primary uppercase">
            {breadcrumb(location.pathname)}
          </div>
          <UserMenu />
        </header>
        <main
          className={cn(
            "w-full flex-1 px-6 py-8",
            wide ? "lg:min-h-0 lg:overflow-hidden" : full ? "mx-auto max-w-[1600px]" : "mx-auto max-w-6xl",
          )}
        >
          <Outlet />
        </main>
      </div>
      <Toaster />
    </div>
  );
}

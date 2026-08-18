import {
  Home,
  KeyRound,
  Moon,
  ScrollText,
  Store,
  Sun,
  TrendingUp,
  Webhook,
  BookOpen,
} from "lucide-react";
import { useTheme } from "next-themes";
import { NavLink, Outlet, useLocation } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Toaster } from "@/components/ui/sonner";
import { cn } from "@/lib/utils";
import { useMe } from "@/api/queries";

const groups = [
  {
    label: "Gateway",
    items: [
      { to: "/", label: "Overview", icon: Home, end: true },
      { to: "/marketplaces", label: "Marketplaces", icon: Store },
    ],
  },
  {
    label: "Governance",
    items: [
      { to: "/audit", label: "Audit log", icon: ScrollText },
      { to: "/adoption", label: "Adoption", icon: TrendingUp },
      { to: "/webhooks", label: "Webhooks", icon: Webhook },
    ],
  },
  {
    label: "Access",
    items: [{ to: "/tokens", label: "Access tokens", icon: KeyRound }],
  },
];

function breadcrumb(pathname: string): string {
  if (pathname === "/") return "Overview";
  if (pathname.startsWith("/marketplaces/")) return "Marketplace detail";
  if (pathname.startsWith("/marketplaces")) return "Marketplaces";
  if (pathname.startsWith("/audit")) return "Audit log";
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

function ModeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  return (
    <Button
      variant="outline"
      size="icon"
      aria-label="Toggle dark mode"
      onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}
    >
      <Sun className="size-4 dark:hidden" aria-hidden />
      <Moon className="hidden size-4 dark:block" aria-hidden />
    </Button>
  );
}

/**
 * Portal shell: grouped sidebar navigation, breadcrumb top bar, session surface,
 * and dark-mode toggle.
 *
 * @Requirements GW_0018
 */
export function AppLayout() {
  const me = useMe();
  const location = useLocation();
  return (
    <div className="flex min-h-screen bg-background text-foreground">
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
                {group.items.map(({ to, label, icon: Icon, end }) => (
                  <li key={to}>
                    <NavLink
                      to={to}
                      end={end}
                      className={({ isActive }) =>
                        cn(
                          "flex items-center gap-2.5 rounded-md px-2.5 py-1.5 text-sm font-medium text-sidebar-foreground hover:bg-sidebar-accent",
                          isActive && "bg-sidebar-primary text-sidebar-primary-foreground hover:bg-sidebar-primary",
                        )
                      }
                    >
                      <Icon className="size-4" aria-hidden />
                      {label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          ))}
          <div>
            <div className="px-2 pb-1 text-[11px] font-semibold tracking-wider text-muted-foreground uppercase">
              Tools
            </div>
            <a
              href="/docs"
              className="flex items-center gap-2.5 rounded-md px-2.5 py-1.5 text-sm font-medium text-sidebar-foreground hover:bg-sidebar-accent"
            >
              <BookOpen className="size-4" aria-hidden />
              API reference
            </a>
          </div>
        </nav>
        <div className="border-t px-4 py-3 text-sm text-muted-foreground">
          {me.data ? <span aria-label="Signed in as">{me.data.username}</span> : null}
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between border-b px-6 py-3">
          <div className="text-xs font-semibold tracking-wider text-primary uppercase">
            {breadcrumb(location.pathname)}
          </div>
          <ModeToggle />
        </header>
        <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8">
          <Outlet />
        </main>
      </div>
      <Toaster />
    </div>
  );
}

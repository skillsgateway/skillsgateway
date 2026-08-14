import { GitBranch, KeyRound, ScrollText, Store } from "lucide-react";
import { NavLink, Outlet } from "react-router-dom";
import { Toaster } from "@/components/ui/sonner";
import { cn } from "@/lib/utils";
import { useMe } from "@/api/queries";

const navigation = [
  { to: "/marketplaces", label: "Marketplaces", icon: Store },
  { to: "/audit", label: "Audit log", icon: ScrollText },
  { to: "/tokens", label: "Access tokens", icon: KeyRound },
];

/**
 * Portal shell: navigation and the authenticated user surface.
 *
 * @Requirements GW_0018
 */
export function AppLayout() {
  const me = useMe();
  return (
    <div className="min-h-screen bg-background">
      <header className="border-b">
        <div className="mx-auto flex max-w-6xl items-center gap-6 px-6 py-3">
          <div className="flex items-center gap-2 font-semibold">
            <GitBranch className="size-5" aria-hidden />
            Skills Gateway
          </div>
          <nav aria-label="Main" className="flex items-center gap-1">
            {navigation.map(({ to, label, icon: Icon }) => (
              <NavLink
                key={to}
                to={to}
                className={({ isActive }) =>
                  cn(
                    "flex items-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium text-muted-foreground hover:bg-accent hover:text-accent-foreground",
                    isActive && "bg-accent text-accent-foreground",
                  )
                }
              >
                <Icon className="size-4" aria-hidden />
                {label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto text-sm text-muted-foreground">
            {me.data ? <span aria-label="Signed in as">{me.data.username}</span> : null}
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-8">
        <Outlet />
      </main>
      <Toaster />
    </div>
  );
}

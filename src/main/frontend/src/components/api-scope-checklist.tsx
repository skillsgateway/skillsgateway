import { useMachineScopes } from "@/api/queries";
import { Checkbox } from "@/components/ui/checkbox";

/**
 * The API scopes a system access token may hold, one checkbox each, read from the gateway's own
 * registry so the list cannot drift from the server's (GW_AUTH_0076). There is no "all" choice:
 * each scope is granted by name. Shared by the create form and the edit dialog so the two offer
 * the same choice in the same words (GW_AUTH_0072, GW_AUTH_0089).
 */
export function ApiScopeChecklist({
  chosen,
  onToggle,
}: {
  chosen: ReadonlySet<string>;
  onToggle: (scope: string, on: boolean) => void;
}) {
  const scopes = useMachineScopes();
  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium">API scopes</legend>
      {scopes.isLoading ? <p className="text-sm text-muted-foreground">Loading…</p> : null}
      {scopes.isError ? (
        <p role="alert" className="text-sm text-destructive">
          {scopes.error.message}
        </p>
      ) : null}
      <div className="grid max-h-56 gap-1 overflow-y-auto rounded-md border p-2 sm:grid-cols-2">
        {(scopes.data ?? []).map((scope) => (
          <label
            key={scope.scope}
            className="flex items-center gap-2 font-mono text-xs"
            title={(scope.routes ?? []).join("\n")}
          >
            <Checkbox
              aria-label={scope.scope}
              aria-describedby={`scope-routes-${scope.scope}`}
              checked={chosen.has(scope.scope ?? "")}
              onCheckedChange={(checked) => onToggle(scope.scope ?? "", Boolean(checked))}
            />
            {scope.scope}
            {/* The routes a scope reaches, for assistive technology and for the hover title:
                a tooltip alone is out of reach of a keyboard or a touch screen. */}
            <span id={`scope-routes-${scope.scope}`} className="sr-only">
              Reaches {(scope.routes ?? []).join(", ")}
            </span>
          </label>
        ))}
      </div>
      <p className="text-xs text-muted-foreground">
        There is no “all” choice: each scope is granted by name, and hovering one lists the routes it
        reaches.
      </p>
    </fieldset>
  );
}

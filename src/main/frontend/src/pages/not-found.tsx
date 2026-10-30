import { Link } from "react-router-dom";

/**
 * Any address the portal does not have. The gateway answers it with the portal and a 404, so a
 * mistyped or stale link lands here, inside the shell, rather than on a bare error document.
 *
 * @Requirements GW_AUTH_0067
 */
export function NotFoundPage() {
  return (
    <div className="space-y-3">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <p className="text-sm text-muted-foreground">
        The portal has no page at this address. It may have been mistyped, or the link may be from an
        older version.
      </p>
      <p className="text-sm">
        <Link to="/" className="font-medium text-primary underline-offset-4 hover:underline">
          Go to the overview
        </Link>
      </p>
    </div>
  );
}

import { useState } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import {
  useCreateToken,
  useIsAdmin,
  useRevokeToken,
  useTokens,
  type IssuedToken,
  type TokenView,
} from "@/api/queries";
import { EditTokenScopesDialog } from "@/components/edit-token-scopes-dialog";
import { IssuedTokenDialog } from "@/components/issued-token-dialog";
import { MarketplaceScopePicker, type ScopeSelection } from "@/components/marketplace-scope-picker";
import { SegmentedGroup } from "@/components/segmented-group";
import { Timestamp } from "@/components/timestamp";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { DEFAULT_LIFETIME, expiryFor, LIFETIMES, type Lifetime } from "@/lib/token-lifetimes";
import { scopeChoiceValid, scopesFor, sortedNames } from "@/lib/token-scope";

export { IssuedTokenDialog };

/** An expiry in the past: the server no longer accepts the token, though it is still listed. */
function expired(token: TokenView): boolean {
  return token.expiresAt !== undefined && token.expiresAt !== null && Date.parse(token.expiresAt) <= Date.now();
}

/** How many marketplace names a row shows before the rest are summarised; the whole list stays readable. */
const REACH_NAMES_SHOWN = 3;

/**
 * What a token can fetch, in words (GW_AUTH_0080): "every marketplace" for an unscoped token
 * rather than an empty cell, the names for a scoped one, and the hosted marketplaces it may
 * publish to only where it has any (the API grants none by default, so "none" would be noise on
 * every row).
 *
 * @Requirements GW_AUTH_0080
 */
export function TokenReach({ token }: { token: TokenView }) {
  const scopes = sortedNames(token.scopes ?? []);
  const pushes = sortedNames(token.pushScopes ?? []);
  const shown = scopes.slice(0, REACH_NAMES_SHOWN);
  const rest = scopes.slice(REACH_NAMES_SHOWN);
  return (
    <div className="space-y-1">
      {scopes.length === 0 ? (
        <span className="text-muted-foreground">every marketplace</span>
      ) : (
        <span className="flex flex-wrap items-center gap-1" title={scopes.join(", ")}>
          {shown.map((name) => (
            <span key={name} className="rounded-md border bg-muted px-2 py-0.5 font-mono text-xs break-all">
              {name}
            </span>
          ))}
          {rest.length > 0 ? (
            <span className="text-xs text-muted-foreground">
              +{rest.length} more<span className="sr-only">: {rest.join(", ")}</span>
            </span>
          ) : null}
        </span>
      )}
      {pushes.length > 0 ? (
        <div className="text-xs text-muted-foreground">publishes to: {pushes.join(", ")}</div>
      ) : null}
    </div>
  );
}

/**
 * The token list. Presentational: it renders what it is given and reports a revocation upwards,
 * so both states of the Last used column can be seen in a story without a server.
 *
 * "Last used" is relative because the question it answers is recency, and the exact instant stays
 * one hover away. "never" rather than the em dash used elsewhere: a credential nothing has
 * authenticated with is a fact worth reading, not an absent value — and the same for a token that
 * never expires.
 *
 * @Requirements GW_AUTH_0031, GW_AUTH_0070, GW_AUTH_0080
 */
export function TokenTable({
  tokens,
  onRevoke,
  onEdit,
  revoking = false,
}: {
  tokens: TokenView[];
  onRevoke: (token: TokenView) => void;
  /** Offered for a live token only (GW_AUTH_0088); absent, the list is read-and-revoke as before. */
  onEdit?: (token: TokenView) => void;
  revoking?: boolean;
}) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Name</TableHead>
          <TableHead>Reach</TableHead>
          <TableHead>Created</TableHead>
          <TableHead>Last used</TableHead>
          <TableHead>Expires</TableHead>
          <TableHead>Status</TableHead>
          <TableHead className="text-right">Actions</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {tokens.map((token) => (
          <TableRow key={token.id}>
            <TableCell>
              {/* The server stores the name verbatim, with no length limit; cut here, whole on hover. */}
              <span className="block max-w-64 truncate" title={token.name}>
                {token.name}
              </span>
            </TableCell>
            <TableCell>
              <TokenReach token={token} />
            </TableCell>
            <TableCell><Timestamp value={token.createdAt} /></TableCell>
            <TableCell>
              <Timestamp
                value={token.lastUsedAt}
                relative
                absent="never"
                className={token.lastUsedAt ? undefined : "text-muted-foreground"}
              />
            </TableCell>
            <TableCell>
              <Timestamp
                value={token.expiresAt}
                absent="never"
                className={token.expiresAt ? undefined : "text-muted-foreground"}
              />
            </TableCell>
            <TableCell>
              {token.revokedAt ? (
                <Badge variant="destructive">revoked</Badge>
              ) : expired(token) ? (
                <Badge variant="secondary">expired</Badge>
              ) : (
                <Badge>active</Badge>
              )}
            </TableCell>
            <TableCell className="space-x-2 text-right whitespace-nowrap">
              {onEdit && !token.revokedAt && !expired(token) ? (
                <Button
                  size="sm"
                  variant="outline"
                  aria-label={`Edit reach of token ${token.name}`}
                  onClick={() => onEdit(token)}
                >
                  Edit reach
                </Button>
              ) : null}
              {!token.revokedAt ? (
                <Button
                  size="sm"
                  variant="destructive"
                  aria-label={`Revoke token ${token.name}`}
                  onClick={() => onRevoke(token)}
                  disabled={revoking}
                >
                  Revoke
                </Button>
              ) : null}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

/**
 * Personal access token self-service: create with a chosen lifetime and show-once cleartext, revoke.
 *
 * @Requirements GW_AUTH_0005, GW_AUTH_0070, GW_AUTH_0071, GW_AUTH_0078, GW_AUTH_0079, GW_AUTH_0081, GW_AUTH_0088
 */
export function TokensPage() {
  const tokens = useTokens();
  const isAdmin = useIsAdmin();
  const create = useCreateToken();
  const revoke = useRevokeToken();
  const [name, setName] = useState("");
  const [lifetime, setLifetime] = useState<Lifetime>(DEFAULT_LIFETIME);
  const [scope, setScope] = useState<ScopeSelection>({ choice: "every", chosen: [] });
  const [issued, setIssued] = useState<IssuedToken | null>(null);
  const [editing, setEditing] = useState<TokenView | null>(null);

  // The server stores the name verbatim (NOT NULL, no further constraint), so the only
  // client-side rule is the one that keeps a blank name out of the list.
  const trimmedName = name.trim();
  // A limited choice that names nothing is not creatable: the API would read it as every marketplace.
  const canCreate = trimmedName.length > 0 && scopeChoiceValid(scope.choice, scope.chosen) && !create.isPending;

  const onCreate = (event: React.FormEvent) => {
    event.preventDefault();
    if (!trimmedName) {
      toast.error("Token name is required");
      return;
    }
    create.mutate(
      { name: trimmedName, expiresAt: expiryFor(lifetime), scopes: scopesFor(scope.choice, scope.chosen) },
      {
        onSuccess: (token) => {
          setIssued(token);
          setName("");
          // While this page stays mounted its observer keeps the mutation's result, cleartext
          // included, and `gcTime` only counts once the observer lets go: release it now that
          // the dialog holds the value (GW_AUTH_0081).
          create.reset();
        },
      },
    );
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Personal access tokens</h1>
        <p className="text-sm text-muted-foreground">
          Personal access tokens are yours: they authenticate your git clients against the facade.
          Values are hashed at rest and shown exactly once.
        </p>
        {isAdmin ? (
          <p className="text-sm text-muted-foreground">
            Looking for a token for a pipeline or another service? That is a{" "}
            <Link
              to="/configuration/tokens"
              className="font-medium text-primary underline-offset-4 hover:underline"
            >
              system access token
            </Link>
            , managed under Configuration.
          </p>
        ) : null}
      </div>
      <form onSubmit={onCreate} className="space-y-2">
        <div className="flex flex-wrap items-end gap-3">
          <div className="space-y-2">
            <Label htmlFor="token-name">Token name</Label>
            <Input
              id="token-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              autoComplete="off"
              placeholder="ci-runner"
              aria-describedby="token-name-hint"
            />
          </div>
          <SegmentedGroup
            label="Expires"
            value={lifetime}
            options={LIFETIMES}
            onChange={(next) => {
              setLifetime(next);
              create.reset();
            }}
            describedBy="token-name-hint"
          />
          <Button type="submit" disabled={!canCreate}>
            {create.isPending ? "Creating…" : "Create token"}
          </Button>
        </div>
        <MarketplaceScopePicker
          value={scope}
          onChange={(next) => {
            setScope(next);
            create.reset();
          }}
        />
        <p id="token-name-hint" className="text-xs text-muted-foreground">
          A name is required — it identifies the token in this list. The lifetime defaults to 30
          days and is never unlimited; this gateway may cap it lower, in which case a longer choice
          is refused. Which marketplaces it fetches is chosen below.
        </p>
        {create.isError ? (
          // The server's lifetime cap is the one rule this form cannot mirror, so its refusal stays
          // beside the choice until another is made.
          <p role="alert" className="text-xs text-destructive">
            {create.error.message}
          </p>
        ) : null}
      </form>
      {tokens.isLoading ? <p>Loading…</p> : null}
      {tokens.isError ? (
        <p role="alert" className="text-sm text-destructive">
          {tokens.error.message}
        </p>
      ) : null}
      {tokens.data?.length === 0 ? (
        <p className="text-sm text-muted-foreground">No tokens yet.</p>
      ) : null}
      {tokens.data && tokens.data.length > 0 ? (
        <TokenTable
          tokens={tokens.data}
          revoking={revoke.isPending}
          onEdit={setEditing}
          onRevoke={(token) =>
            revoke.mutate(token.id ?? 0, {
              onSuccess: () => toast.success(`Token '${token.name}' revoked`),
              onError: (error) => toast.error(error.message),
            })
          }
        />
      ) : null}
      {issued ? <IssuedTokenDialog issued={issued} onClose={() => setIssued(null)} /> : null}
      {editing ? <EditTokenScopesDialog token={editing} onClose={() => setEditing(null)} /> : null}
    </div>
  );
}

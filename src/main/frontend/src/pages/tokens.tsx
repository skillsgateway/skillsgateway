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
import { IssuedTokenDialog } from "@/components/issued-token-dialog";
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

export { IssuedTokenDialog };

/** An expiry in the past: the server no longer accepts the token, though it is still listed. */
function expired(token: TokenView): boolean {
  return token.expiresAt !== undefined && token.expiresAt !== null && Date.parse(token.expiresAt) <= Date.now();
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
 * @Requirements GW_AUTH_0031, GW_AUTH_0070
 */
export function TokenTable({
  tokens,
  onRevoke,
  revoking = false,
}: {
  tokens: TokenView[];
  onRevoke: (token: TokenView) => void;
  revoking?: boolean;
}) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Name</TableHead>
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
            <TableCell className="text-right">
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
 * @Requirements GW_AUTH_0005, GW_AUTH_0070, GW_AUTH_0071, GW_AUTH_0078
 */
export function TokensPage() {
  const tokens = useTokens();
  const isAdmin = useIsAdmin();
  const create = useCreateToken();
  const revoke = useRevokeToken();
  const [name, setName] = useState("");
  const [lifetime, setLifetime] = useState<Lifetime>(DEFAULT_LIFETIME);
  const [issued, setIssued] = useState<IssuedToken | null>(null);

  // The server stores the name verbatim (NOT NULL, no further constraint), so the only
  // client-side rule is the one that keeps a blank name out of the list.
  const trimmedName = name.trim();
  const canCreate = trimmedName.length > 0 && !create.isPending;

  const onCreate = (event: React.FormEvent) => {
    event.preventDefault();
    if (!trimmedName) {
      toast.error("Token name is required");
      return;
    }
    create.mutate({ name: trimmedName, expiresAt: expiryFor(lifetime) }, {
      onSuccess: (token) => {
        setIssued(token);
        setName("");
      },
    });
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
        <p id="token-name-hint" className="text-xs text-muted-foreground">
          A name is required — it identifies the token in this list. The lifetime defaults to 30
          days and is never unlimited; this gateway may cap it lower, in which case a longer choice
          is refused.
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
          onRevoke={(token) =>
            revoke.mutate(token.id ?? 0, {
              onSuccess: () => toast.success(`Token '${token.name}' revoked`),
              onError: (error) => toast.error(error.message),
            })
          }
        />
      ) : null}
      {issued ? <IssuedTokenDialog issued={issued} onClose={() => setIssued(null)} /> : null}
    </div>
  );
}

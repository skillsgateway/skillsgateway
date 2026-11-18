import { Ellipsis } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import {
  useCreateMachineToken,
  useMachineTokens,
  useRevokeMachineToken,
  useRotateMachineToken,
  type IssuedToken,
  type MachineCredentialView,
} from "@/api/queries";
import { ApiScopeChecklist } from "@/components/api-scope-checklist";
import { EditSystemTokenScopesDialog } from "@/components/edit-system-token-scopes-dialog";
import { IssuedTokenDialog } from "@/components/issued-token-dialog";
import { RoleGate } from "@/components/role-required";
import { SegmentedGroup } from "@/components/segmented-group";
import { Timestamp } from "@/components/timestamp";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
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
import { marksOf, stateOf, EXPIRING_WITHIN_DAYS } from "@/lib/system-token-marks";
import { SYSTEM_LIFETIMES, systemExpiryFor, type SystemLifetime } from "@/lib/token-lifetimes";

const TITLE = "System access tokens";
const DESCRIPTION =
  "Credentials for pipelines and other services that call the REST API on their own, such as a Terraform provider or a CI job.";

/** The administrator-only page. Everything behind it is read only after the role is established. */
export function SystemTokensPage() {
  return (
    <RoleGate
      role="admin"
      title={TITLE}
      description={DESCRIPTION}
      explanation={
        <>
          A system access token acts with the scopes it was given and outlives the session that
          minted it, so only administrators create, rotate or revoke one. Your own tokens, for your
          git clients, are under{" "}
          <Link to="/security/tokens" className="font-medium text-primary underline-offset-4 hover:underline">
            Security
          </Link>
          .
        </>
      }
    >
      <SystemTokens />
    </RoleGate>
  );
}

type Confirm = { action: "rotate" | "revoke"; token: MachineCredentialView };
type Issued = { issued: IssuedToken; verb: "created" | "rotated" };

/**
 * Create, list, rotate and revoke system access tokens — the portal's face on the API's machine
 * API credential endpoints, with the API's own refusals shown as it states them.
 *
 * @Requirements GW_AUTH_0072, GW_AUTH_0073, GW_AUTH_0074, GW_AUTH_0077, GW_AUTH_0089
 */
function SystemTokens() {
  const tokens = useMachineTokens();
  const create = useCreateMachineToken();
  const rotate = useRotateMachineToken();
  const revoke = useRevokeMachineToken();

  const [principal, setPrincipal] = useState("");
  const [name, setName] = useState("");
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  // Nothing preselected: the lifetime of a credential nobody will be watching is a decision.
  const [lifetime, setLifetime] = useState<SystemLifetime | "">("");
  const [issued, setIssued] = useState<Issued | null>(null);
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  const [editing, setEditing] = useState<MachineCredentialView | null>(null);

  const canCreate =
    principal.trim() !== "" &&
    name.trim() !== "" &&
    chosen.size > 0 &&
    lifetime !== "" &&
    !create.isPending;

  const onCreate = (event: React.FormEvent) => {
    event.preventDefault();
    if (!canCreate) return; // narrows `lifetime`: canCreate includes `lifetime !== ""`
    create.mutate(
      {
        principal: principal.trim(),
        name: name.trim(),
        apiScopes: [...chosen].sort(),
        ...systemExpiryFor(lifetime),
      },
      {
        onSuccess: (token) => {
          setIssued({ issued: token, verb: "created" });
          // The page holds the cleartext now; the mutation must not. While this page stays
          // mounted its observer keeps the result (and `gcTime` only starts counting once the
          // observer lets go), so reset releases it at once (GW_AUTH_0073).
          create.reset();
          setPrincipal("");
          setName("");
          setChosen(new Set());
          setLifetime("");
        },
      },
    );
  };

  const toggle = (scope: string, on: boolean) => {
    setChosen((current) => {
      const next = new Set(current);
      if (on) next.add(scope);
      else next.delete(scope);
      return next;
    });
    create.reset();
  };

  const onConfirm = () => {
    if (!confirm) return;
    const { action, token } = confirm;
    setConfirm(null);
    if (action === "rotate") {
      rotate.mutate(token.id ?? 0, {
        onSuccess: (fresh) => {
          setIssued({ issued: fresh, verb: "rotated" });
          rotate.reset(); // as for create: the mutation must not keep the cleartext
        },
        onError: (error) => toast.error(error.message),
      });
    } else {
      revoke.mutate(token.id ?? 0, {
        onSuccess: () => toast.success(`Token '${token.name}' revoked`),
        onError: (error) => toast.error(error.message),
      });
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">{TITLE}</h1>
        <p className="text-sm text-muted-foreground">
          {DESCRIPTION} The API calls these <em>machine API credentials</em>. Values are hashed at
          rest and shown exactly once.
        </p>
        <p className="text-sm text-muted-foreground">
          These are not your own tokens. A token for your git client is a{" "}
          <Link to="/security/tokens" className="font-medium text-primary underline-offset-4 hover:underline">
            personal access token
          </Link>
          , under Security.
        </p>
      </div>

      <form onSubmit={onCreate} className="space-y-4" aria-labelledby="system-token-create">
        <h2 id="system-token-create" className="text-lg font-medium">
          Create a system access token
        </h2>
        <div className="flex flex-wrap items-end gap-3">
          <div className="space-y-2">
            <Label htmlFor="system-token-principal">Principal</Label>
            <Input
              id="system-token-principal"
              value={principal}
              onChange={(event) => setPrincipal(event.target.value)}
              autoComplete="off"
              placeholder="terraform-ci"
              aria-describedby="system-token-hint"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="system-token-name">Token name</Label>
            <Input
              id="system-token-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              autoComplete="off"
              placeholder="platform-pipeline"
              aria-describedby="system-token-hint"
            />
          </div>
        </div>

        <ApiScopeChecklist chosen={chosen} onToggle={toggle} />

        <div className="flex flex-wrap items-end gap-3">
          <SegmentedGroup<SystemLifetime | "">
            label="Expires"
            value={lifetime}
            options={SYSTEM_LIFETIMES}
            onChange={(next) => {
              setLifetime(next);
              create.reset();
            }}
            describedBy="system-token-hint"
          />
          <Button type="submit" disabled={!canCreate}>
            {create.isPending ? "Creating…" : "Create token"}
          </Button>
        </div>
        <p id="system-token-hint" className="text-xs text-muted-foreground">
          Principal, name, at least one scope and a lifetime are required. The lifetime is your
          choice — nothing is preselected. A gateway that configures a token lifetime cap refuses
          the longer choices, and “Never”, and says so below.
        </p>
        {create.isError ? (
          // The server's refusal, as the API states it: unknown scope, no lifetime, beyond a cap.
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
        <p className="text-sm text-muted-foreground">No system access tokens yet.</p>
      ) : null}
      {tokens.data && tokens.data.length > 0 ? (
        <SystemTokenTable
          tokens={tokens.data}
          busy={rotate.isPending || revoke.isPending}
          onEdit={setEditing}
          onRotate={(token) => setConfirm({ action: "rotate", token })}
          onRevoke={(token) => setConfirm({ action: "revoke", token })}
        />
      ) : null}

      {confirm ? (
        <Dialog open onOpenChange={(open) => (open ? undefined : setConfirm(null))}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle className="wrap-anywhere">
                {confirm.action === "rotate" ? "Rotate" : "Revoke"} token '{confirm.token.name}'?
              </DialogTitle>
              <DialogDescription>
                {confirm.action === "rotate"
                  ? "A new secret with the same principal, scopes and expiry replaces this one, and the old secret stops working at once. Whatever uses it must be given the new value."
                  : "This is immediate and permanent: the next request the token makes is refused. It cannot be undone."}
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button variant="outline" onClick={() => setConfirm(null)}>
                Cancel
              </Button>
              <Button
                variant={confirm.action === "revoke" ? "destructive" : "default"}
                onClick={onConfirm}
              >
                {confirm.action === "rotate" ? "Rotate token" : "Revoke token"}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      ) : null}

      {editing ? <EditSystemTokenScopesDialog token={editing} onClose={() => setEditing(null)} /> : null}

      {issued ? (
        <IssuedTokenDialog
          issued={issued.issued}
          verb={issued.verb}
          kind="System access token"
          onClose={() => setIssued(null)}
        />
      ) : null}
    </div>
  );
}

/**
 * The list. Presentational, so every state — expiring, never used, never expiring, revoked — can
 * be seen in a story without a server. Marks are words with the date beside them, never colour
 * alone.
 *
 * @Requirements GW_AUTH_0072, GW_AUTH_0074
 */
export function SystemTokenTable({
  tokens,
  onEdit,
  onRotate,
  onRevoke,
  busy = false,
  now,
}: {
  tokens: MachineCredentialView[];
  /** Offered for a live token only (GW_AUTH_0089). */
  onEdit?: (token: MachineCredentialView) => void;
  onRotate: (token: MachineCredentialView) => void;
  onRevoke: (token: MachineCredentialView) => void;
  busy?: boolean;
  now?: number;
}) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Name</TableHead>
          <TableHead>Principal</TableHead>
          <TableHead>Owner</TableHead>
          <TableHead>Scopes</TableHead>
          <TableHead>Expires</TableHead>
          <TableHead>Last used</TableHead>
          <TableHead>Status</TableHead>
          <TableHead className="text-right">Actions</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {tokens.map((token) => {
          const state = stateOf(token, now);
          const marks = marksOf(token, now);
          return (
            <TableRow key={token.id}>
              <TableCell>
                <span className="block max-w-48 truncate" title={token.name}>
                  {token.name}
                </span>
              </TableCell>
              <TableCell className="font-mono text-xs whitespace-normal wrap-anywhere">{token.principal}</TableCell>
              <TableCell className="whitespace-normal wrap-anywhere">{token.machineOwner}</TableCell>
              <TableCell className="max-w-64 font-mono text-xs whitespace-normal break-words">
                {(token.apiScopes ?? []).join(", ")}
              </TableCell>
              <TableCell className="whitespace-normal">
                <div className="space-y-1">
                  <Timestamp
                    value={token.expiresAt}
                    absent="never expires"
                    // A credential that never lapses is the riskiest state in the list: said at
                    // full weight, not greyed out like an absent value.
                    className={token.expiresAt ? undefined : "font-medium"}
                  />
                  {marks.expiringSoon ? (
                    <div>
                      <Badge variant="secondary">expires within {EXPIRING_WITHIN_DAYS} days</Badge>
                    </div>
                  ) : null}
                </div>
              </TableCell>
              <TableCell className="whitespace-normal">
                <div className="space-y-1">
                  {marks.neverUsed ? (
                    // The mark is the statement: a plain "never" above it would say it twice.
                    <Badge variant="secondary">never used</Badge>
                  ) : (
                    <Timestamp
                      value={token.lastUsedAt}
                      relative
                      absent="never"
                      className={token.lastUsedAt ? undefined : "text-muted-foreground"}
                    />
                  )}
                </div>
              </TableCell>
              <TableCell>
                {state === "revoked" ? (
                  <Badge variant="destructive">revoked</Badge>
                ) : state === "expired" ? (
                  <Badge variant="secondary">expired</Badge>
                ) : (
                  <Badge>active</Badge>
                )}
              </TableCell>
              <TableCell className="text-right">
                {state === "active" ? (
                  // One trigger instead of three buttons: the cell stays a fixed width whatever the
                  // row holds, so no action can be pushed past the edge of the scrolling container.
                  <DropdownMenu>
                    <DropdownMenuTrigger
                      render={
                        <Button
                          size="icon"
                          variant="outline"
                          aria-label={`Actions for token ${token.name}`}
                          disabled={busy}
                        >
                          <Ellipsis className="size-4" aria-hidden />
                        </Button>
                      }
                    />
                    <DropdownMenuContent align="end">
                      {onEdit ? <DropdownMenuItem onClick={() => onEdit(token)}>Edit scopes…</DropdownMenuItem> : null}
                      <DropdownMenuItem onClick={() => onRotate(token)}>Rotate…</DropdownMenuItem>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem variant="destructive" onClick={() => onRevoke(token)}>
                        Revoke…
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                ) : null}
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}

import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { useCreateToken, useRevokeToken, useTokens, type IssuedToken } from "@/api/queries";
import { Timestamp } from "@/components/timestamp";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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

export function IssuedTokenDialog({ issued, onClose }: { issued: IssuedToken; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(issued.token ?? "");
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error("Could not access the clipboard");
    }
  };
  return (
    <Dialog open onOpenChange={(open) => (open ? undefined : onClose())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Token '{issued.name}' created</DialogTitle>
          <DialogDescription>
            This value is shown exactly once — copy it now. Only a hash is stored.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          <Label htmlFor="token-cleartext-box">Personal access token</Label>
          <div id="token-cleartext-box" className="flex items-center gap-2 rounded-md border bg-muted p-3">
            <code data-testid="token-cleartext" className="flex-1 break-all text-sm">
              {issued.token}
            </code>
            <Button
              variant="outline"
              size="icon"
              aria-label="Copy token to clipboard"
              onClick={() => void copy()}
            >
              {copied ? <Check className="size-4" aria-hidden /> : <Copy className="size-4" aria-hidden />}
            </Button>
          </div>
        </div>
        <DialogFooter>
          <Button onClick={onClose}>Done</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Personal access token self-service: create with show-once cleartext, revoke.
 *
 * @Requirements GW_0019
 */
export function TokensPage() {
  const tokens = useTokens();
  const create = useCreateToken();
  const revoke = useRevokeToken();
  const [name, setName] = useState("");
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
    create.mutate(trimmedName, {
      onSuccess: (token) => {
        setIssued(token);
        setName("");
      },
      onError: (error) => toast.error(error.message),
    });
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Access tokens</h1>
        <p className="text-sm text-muted-foreground">
          Personal access tokens authenticate git clients against the facade. Values are hashed at
          rest and shown exactly once.
        </p>
      </div>
      <form onSubmit={onCreate} className="space-y-2">
        <div className="flex items-end gap-3">
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
          <Button type="submit" disabled={!canCreate}>
            {create.isPending ? "Creating…" : "Create token"}
          </Button>
        </div>
        <p id="token-name-hint" className="text-xs text-muted-foreground">
          A name is required — it identifies the token in this list.
        </p>
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
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Created</TableHead>
              <TableHead>Status</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {tokens.data.map((token) => (
              <TableRow key={token.id}>
                <TableCell>{token.name}</TableCell>
                <TableCell><Timestamp value={token.createdAt} /></TableCell>
                <TableCell>
                  {token.revokedAt ? <Badge variant="destructive">revoked</Badge> : <Badge>active</Badge>}
                </TableCell>
                <TableCell className="text-right">
                  {!token.revokedAt ? (
                    <Button
                      size="sm"
                      variant="destructive"
                      aria-label={`Revoke token ${token.name}`}
                      onClick={() =>
                        revoke.mutate(token.id ?? 0, {
                          onSuccess: () => toast.success(`Token '${token.name}' revoked`),
                          onError: (error) => toast.error(error.message),
                        })
                      }
                      disabled={revoke.isPending}
                    >
                      Revoke
                    </Button>
                  ) : null}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : null}
      {issued ? <IssuedTokenDialog issued={issued} onClose={() => setIssued(null)} /> : null}
    </div>
  );
}

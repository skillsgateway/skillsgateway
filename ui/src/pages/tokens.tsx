import { useState } from "react";
import { toast } from "sonner";
import { useCreateToken, useRevokeToken, useTokens, type IssuedToken } from "@/api/queries";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
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
  return (
    <Dialog open onOpenChange={(open) => (open ? undefined : onClose())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Token '{issued.name}' created</DialogTitle>
          <DialogDescription>
            This value is shown exactly once — copy it now. Only a hash is stored.
          </DialogDescription>
        </DialogHeader>
        <Alert>
          <AlertTitle>Personal access token</AlertTitle>
          <AlertDescription>
            <code data-testid="token-cleartext" className="break-all">
              {issued.token}
            </code>
          </AlertDescription>
        </Alert>
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

  const onCreate = (event: React.FormEvent) => {
    event.preventDefault();
    if (!name.trim()) {
      toast.error("Token name is required");
      return;
    }
    create.mutate(name.trim(), {
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
      <form onSubmit={onCreate} className="flex items-end gap-3">
        <div className="space-y-2">
          <Label htmlFor="token-name">Token name</Label>
          <Input
            id="token-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            autoComplete="off"
            placeholder="ci-runner"
          />
        </div>
        <Button type="submit" disabled={create.isPending}>
          {create.isPending ? "Creating…" : "Create token"}
        </Button>
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
                <TableCell>{token.createdAt}</TableCell>
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

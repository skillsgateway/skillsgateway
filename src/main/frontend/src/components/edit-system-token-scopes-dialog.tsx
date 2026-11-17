import { useId, useState } from "react";
import { toast } from "sonner";
import { useUpdateMachineTokenScopes, type MachineCredentialView } from "@/api/queries";
import { ApiScopeChecklist } from "@/components/api-scope-checklist";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { sameReach, sortedNames } from "@/lib/token-scope";

/**
 * Changes the API scopes of a live system access token (GW_AUTH_0089), with the checklist the create
 * form uses, starting from the scopes the token holds. Only the scopes change: the principal,
 * expiry and secret stay, so whoever already holds the secret has the new scopes on its next
 * request. That is the difference from minting another token, so a choice that adds scopes says it,
 * above the button.
 *
 * Saving stays disabled, with the reason on screen, until at least one scope is ticked (the server
 * refuses a machine credential with none; to remove them all, revoke it) and the choice differs
 * from what the token holds. The server's refusal is shown as the API states it.
 *
 * @Requirements GW_AUTH_0089
 */
export function EditSystemTokenScopesDialog({
  token,
  onClose,
}: {
  token: MachineCredentialView;
  onClose: () => void;
}) {
  const update = useUpdateMachineTokenScopes();
  const current = token.apiScopes ?? [];
  const [chosen, setChosen] = useState<Set<string>>(() => new Set(current));
  const hintId = useId();

  const next = sortedNames([...chosen]);
  const changed = !sameReach(current, next);
  const canSave = next.length > 0 && changed && !update.isPending;
  const held = new Set(current);
  const added = next.filter((scope) => !held.has(scope));

  const hint =
    next.length === 0
      ? "A system access token must hold at least one scope. To remove them all, revoke the token."
      : !changed
        ? "These are the token's current scopes. Change them to save."
        : "Saving changes the scopes now. The principal, expiry and secret stay the same.";

  const toggle = (scope: string, on: boolean) => {
    setChosen((existing) => {
      const copy = new Set(existing);
      if (on) copy.add(scope);
      else copy.delete(scope);
      return copy;
    });
    update.reset();
  };

  const onSave = (event: React.FormEvent) => {
    event.preventDefault();
    if (!canSave || token.id === undefined) return;
    update.mutate(
      { id: token.id, apiScopes: next },
      {
        onSuccess: () => {
          toast.success(`Scopes of '${token.name}' changed`);
          onClose();
        },
      },
    );
  };

  return (
    <Dialog open onOpenChange={(open) => (open ? undefined : onClose())}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle className="wrap-anywhere">Edit scopes of '{token.name}'</DialogTitle>
          <DialogDescription>
            The API scopes this token holds. The secret does not change, so whatever uses it has the
            new scopes on its next request.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={onSave} className="space-y-3">
          <ApiScopeChecklist chosen={chosen} onToggle={toggle} />
          {added.length > 0 && changed ? (
            // A polite status, so a screen reader hears the warning when a choice starts adding scopes.
            <p data-testid="widening-warning" role="status" className="text-xs text-foreground">
              This adds {added.join(", ")}: whoever holds the existing secret has {added.length === 1 ? "it" : "them"}{" "}
              from the next request.
            </p>
          ) : null}
          <p id={hintId} className="text-xs text-muted-foreground">
            {hint}
          </p>
          {update.isError ? (
            // The API's own refusal: an unknown scope, or a credential that is no longer live.
            <p role="alert" className="text-xs text-destructive">
              {update.error.message}
            </p>
          ) : null}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={update.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={!canSave} aria-describedby={hintId}>
              {update.isPending ? "Saving…" : "Save scopes"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

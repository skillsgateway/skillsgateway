import { useId, useState } from "react";
import { toast } from "sonner";
import { useUpdateTokenScopes, type TokenView } from "@/api/queries";
import { MarketplaceScopePicker, type ScopeSelection } from "@/components/marketplace-scope-picker";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  sameReach,
  scopeChoiceValid,
  scopesForUpdate,
  selectionFor,
  sortedNames,
  widening,
} from "@/lib/token-scope";

/**
 * Changes which marketplaces a live personal access token may fetch (GW_AUTH_0088), with the same
 * choice the create form uses, starting from the token's current reach. Only the reach changes:
 * the secret, expiry and name stay, so the secret already in use follows the new reach on its next
 * request and nothing has to be handed out again.
 *
 * Saving stays disabled, with the reason on screen, until the choice is valid (a limited choice
 * names a marketplace: an empty list would grant every marketplace) and differs from the current
 * reach. A choice that widens is said so before it is saved. The server's refusal is shown as the
 * API states it; the marketplace names are not re-checked here, since its registry is
 * authoritative.
 *
 * @Requirements GW_AUTH_0088
 */
export function EditTokenScopesDialog({ token, onClose }: { token: TokenView; onClose: () => void }) {
  const update = useUpdateTokenScopes();
  const current = token.scopes ?? [];
  const [scope, setScope] = useState<ScopeSelection>(() => selectionFor(current));
  const hintId = useId();

  const next = scopesForUpdate(scope.choice, scope.chosen);
  const valid = scopeChoiceValid(scope.choice, scope.chosen);
  const changed = !sameReach(current, next);
  const canSave = valid && changed && !update.isPending;
  const added = valid ? widening(current, next) : null;

  const hint = !valid
    ? "Choose at least one marketplace, or Every marketplace: a token that names none would fetch every marketplace."
    : !changed
      ? "This is the token's current reach. Change it to save."
      : "Saving changes the reach now. The secret and the expiry stay the same.";

  const onSave = (event: React.FormEvent) => {
    event.preventDefault();
    if (!canSave || token.id === undefined) return;
    update.mutate(
      { id: token.id, scopes: next },
      {
        onSuccess: () => {
          toast.success(`Reach of '${token.name}' changed`);
          onClose();
        },
      },
    );
  };

  return (
    <Dialog open onOpenChange={(open) => (open ? undefined : onClose())}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle className="wrap-anywhere">Edit reach of '{token.name}'</DialogTitle>
          <DialogDescription>
            Which marketplaces this token may fetch. The secret does not change, so the token in use
            follows the new reach on its next request.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={onSave} className="space-y-3">
          <MarketplaceScopePicker
            value={scope}
            onChange={(nextSelection) => {
              setScope(nextSelection);
              update.reset();
            }}
          />
          {added ? (
            // A polite status, so a screen reader hears the warning when a choice starts widening.
            <p data-testid="widening-warning" role="status" className="text-xs text-foreground">
              This widens the token:{" "}
              {added === "every"
                ? "it will fetch every marketplace this gateway serves you"
                : `it will also fetch ${sortedNames(added)
                    .map((name) => `'${name}'`)
                    .join(", ")}`}
              , using the secret already in use, from its next request.
            </p>
          ) : null}
          <p id={hintId} className="text-xs text-muted-foreground">
            {hint}
          </p>
          {update.isError ? (
            // The API's own refusal: an unknown marketplace, or a token that is no longer live.
            <p role="alert" className="text-xs text-destructive">
              {update.error.message}
            </p>
          ) : null}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={update.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={!canSave} aria-describedby={hintId}>
              {update.isPending ? "Saving…" : "Save reach"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

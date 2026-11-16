import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import type { IssuedToken } from "@/api/queries";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";

/**
 * The show-once reveal of a token's cleartext, shared by personal and system access tokens so the
 * two cannot drift (GW_AUTH_0073). The secret lives only in the props of this dialog: the caller
 * holds it in component state and drops it on close, and nothing here writes it anywhere else.
 *
 * @Requirements GW_AUTH_0073
 */
export function IssuedTokenDialog({
  issued,
  onClose,
  kind = "Personal access token",
  verb = "created",
}: {
  issued: IssuedToken;
  onClose: () => void;
  /** What the token is called, in the label above the value. */
  kind?: "Personal access token" | "System access token";
  verb?: "created" | "rotated";
}) {
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
          <DialogTitle className="wrap-anywhere">
            Token '{issued.name}' {verb}
          </DialogTitle>
          <DialogDescription>
            This value is shown exactly once and cannot be shown again — copy it now. Only a hash is
            stored.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          <Label htmlFor="token-cleartext-box">{kind}</Label>
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

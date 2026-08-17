import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { useCreateToken } from "@/api/queries";
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

/** Value shown in every snippet until a token is minted inside this wizard instance. */
const TOKEN_PLACEHOLDER = "<YOUR_TOKEN>";

function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error("Could not access the clipboard");
    }
  };
  return (
    <Button variant="outline" size="icon" aria-label={label} onClick={() => void copy()}>
      {copied ? <Check className="size-4" aria-hidden /> : <Copy className="size-4" aria-hidden />}
    </Button>
  );
}

function Snippet({
  title,
  command,
  copyLabel,
  testId,
}: {
  title: string;
  command: string;
  copyLabel: string;
  testId: string;
}) {
  return (
    <div className="space-y-1">
      <div className="text-sm font-medium">{title}</div>
      <div className="flex items-start gap-2 rounded-md border bg-muted p-3">
        <code data-testid={testId} className="min-w-0 flex-1 font-mono text-xs whitespace-pre-wrap break-all">
          {command}
        </code>
        <CopyButton value={command} label={copyLabel} />
      </div>
    </div>
  );
}

/**
 * Client setup wizard: composes the marketplace-add command, the git credential configuration
 * and the clone snippet for one marketplace, every URL derived from the browsing origin — the
 * gateway serves the portal and the facade from the same server, so where the browser is, is
 * where git clients go.
 *
 * A token minted here goes through the exact same show-once creation flow as the tokens page:
 * the cleartext lives only in this component's state, is substituted into the snippets while
 * the wizard stays open, and is gone when it closes — no previously issued secret is ever
 * displayed (the server never returns one, and this component never stores one).
 *
 * @Requirements GW_0079
 */
export function SetupWizard({ marketplace, onClose }: { marketplace: string; onClose: () => void }) {
  const create = useCreateToken();
  const [name, setName] = useState("");
  const [cleartext, setCleartext] = useState<string | null>(null);

  // Same rule as the tokens page it reuses: the server requires a name (NOT NULL), and a
  // whitespace-only one is no name at all.
  const trimmedName = name.trim();
  const canCreate = trimmedName.length > 0 && !create.isPending;

  const origin = window.location.origin;
  const { protocol, host } = window.location;
  const cloneUrl = `${origin}/git/${marketplace}`;
  const token = cleartext ?? TOKEN_PLACEHOLDER;

  const onCreate = (event: React.FormEvent) => {
    event.preventDefault();
    if (!trimmedName) return;
    create.mutate(trimmedName, {
      onSuccess: (issued) => {
        setCleartext(issued.token ?? null);
        setName("");
      },
      onError: (error) => toast.error(error.message),
    });
  };

  return (
    <Dialog open onOpenChange={(open) => (open ? undefined : onClose())}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Set up a client for '{marketplace}'</DialogTitle>
          <DialogDescription>
            Everything below is derived from this page's own address. Git clients authenticate
            with a personal access token, not with your portal session.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-5">
          <form onSubmit={onCreate} className="space-y-2">
            <div className="text-sm font-medium">1. Personal access token</div>
            {cleartext ? (
              <p className="text-xs text-muted-foreground">
                Token created — its value is filled into the snippets below and shown only while
                this wizard is open. Only a hash is stored.
              </p>
            ) : (
              <>
                <div className="flex items-end gap-3">
                  <div className="space-y-2">
                    <Label htmlFor="wizard-token-name">Token name</Label>
                    <Input
                      id="wizard-token-name"
                      value={name}
                      onChange={(event) => setName(event.target.value)}
                      autoComplete="off"
                      placeholder="my-laptop"
                      aria-describedby="wizard-token-name-hint"
                    />
                  </div>
                  <Button type="submit" disabled={!canCreate}>
                    {create.isPending ? "Creating…" : "Create token"}
                  </Button>
                </div>
                <p id="wizard-token-name-hint" className="text-xs text-muted-foreground">
                  A name is required — it identifies the token in your token list. Already have a
                  token? Leave this and put its value where the snippets say {TOKEN_PLACEHOLDER};
                  existing token values are never shown again.
                </p>
              </>
            )}
          </form>
          <Snippet
            title="2. Store the credential"
            command={`printf 'protocol=${protocol.replace(":", "")}\\nhost=${host}\\nusername=token\\npassword=${token}\\n' | git credential approve`}
            copyLabel="Copy git credential configuration"
            testId="wizard-credential-config"
          />
          <Snippet
            title="3. Add the marketplace to Claude Code"
            command={`claude plugin marketplace add ${cloneUrl}`}
            copyLabel="Copy marketplace add command"
            testId="wizard-add-command"
          />
          <Snippet
            title="Or clone directly (CI and other clients)"
            command={`git clone --depth 1 ${protocol}//token:${token}@${host}/git/${marketplace}`}
            copyLabel="Copy clone command"
            testId="wizard-clone-command"
          />
          <p className="text-xs text-muted-foreground">
            The facade serves only the approved snapshot of this marketplace, on the single branch
            'main'. Every fetch is recorded on the audit ledger.
          </p>
        </div>
        <DialogFooter>
          <Button onClick={onClose}>Done</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

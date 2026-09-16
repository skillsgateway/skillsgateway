import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { useCreateToken } from "@/api/queries";
import { SegmentedGroup } from "@/components/segmented-group";
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
import { cn } from "@/lib/utils";

/** Value shown in every snippet until a token is minted inside this wizard instance. */
const TOKEN_PLACEHOLDER = "<YOUR_TOKEN>";

/**
 * The lifetimes offered, and the one chosen by default.
 *
 * Bounded by default rather than permanent: a credential minted to get a laptop working is the
 * one most likely to be forgotten, and a forgotten credential that never expires is a standing
 * grant nobody decided to make. Thirty days is long enough not to be an interruption.
 *
 * The server owns the actual ceiling (`skills-gateway.tokens.max-ttl`) and it is not exposed to
 * the browser, so this list cannot mirror it exactly — the one case design-conventions allows a
 * client rule to be looser. A deployment with a shorter cap refuses the longer options with a
 * 422, and the error is shown; nothing here raises or lowers any policy maximum.
 */
const LIFETIMES = [
  { value: "P7D", label: "7 days", days: 7 },
  { value: "P30D", label: "30 days", days: 30 },
  { value: "P90D", label: "90 days", days: 90 },
  { value: "none", label: "No expiry", days: null },
] as const;

type Lifetime = (typeof LIFETIMES)[number]["value"];

const DEFAULT_LIFETIME: Lifetime = "P30D";

function expiryFor(value: Lifetime): string | undefined {
  const chosen = LIFETIMES.find((lifetime) => lifetime.value === value);
  if (!chosen || chosen.days === null) return undefined;
  const at = new Date(Date.now() + chosen.days * 24 * 60 * 60 * 1000);
  return at.toISOString();
}

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

/**
 * One command with its own copy control.
 *
 * `lead` marks the credential line — the one a consumer who copies a single thing should copy.
 * That emphasis is carried by the box, not by a differently shaped control: all three snippets
 * copy from the same corner with the same icon button, so the set reads as one family.
 */
function Snippet({
  title,
  command,
  copyLabel,
  testId,
  lead = false,
}: {
  title: string;
  command: string;
  copyLabel: string;
  testId: string;
  lead?: boolean;
}) {
  return (
    <div className="space-y-1">
      <div className="text-sm font-medium">{title}</div>
      <div
        className={cn(
          "flex items-start gap-2 rounded-md border p-3",
          lead ? "border-primary/40 bg-primary/5" : "bg-muted",
        )}
      >
        <code data-testid={testId} className="min-w-0 flex-1 font-mono text-xs whitespace-pre-wrap break-all">
          {command}
        </code>
        <CopyButton value={command} label={copyLabel} />
      </div>
    </div>
  );
}

/**
 * What a consumer sees on a marketplace the gateway is not serving yet.
 *
 * Stated in the consumer's terms — the status code their client will print — rather than in the
 * gateway's, because the failure this prevents is reading a correct refusal as a broken gateway.
 * Rendered both here and on the marketplace page: someone who opens the wizard from the header
 * never reads the page top-down.
 */
export function HeldNotice({ marketplace }: { marketplace: string }) {
  return (
    <p
      data-testid="setup-held-notice"
      role="status"
      className="rounded-md border border-dashed bg-muted/50 p-3 text-xs text-muted-foreground"
    >
      Nothing is being served for '{marketplace}' yet. Until a snapshot is approved, a clone is
      answered with <span className="font-mono">404</span> — the commands below are already correct
      and start working the moment one is approved. This is not a credential problem: a wrong or
      revoked token is answered with <span className="font-mono">401</span> instead.
    </p>
  );
}

/**
 * Client setup wizard: composes the git credential configuration, the marketplace-add command
 * and the clone snippet for one marketplace, every URL derived from the browsing origin — the
 * gateway serves the portal and the facade from the same server, so where the browser is, is
 * where git clients go.
 *
 * A token minted here goes through the exact same show-once creation flow as the tokens page:
 * the cleartext lives only in this component's state, is substituted into the snippets while
 * the wizard stays open, and is gone when it closes — no previously issued secret is ever
 * displayed (the server never returns one, and this component never stores one).
 *
 * @Requirements GW_AUTH_0014, GW_AUTH_0043
 */
export function SetupWizard({
  marketplace,
  serving,
  onClose,
}: {
  marketplace: string;
  serving: boolean;
  onClose: () => void;
}) {
  const create = useCreateToken();
  // Defaulted, not blank: the name identifies the credential in a later revocation, and a
  // consumer who has not thought about it is better served by a name that says where it came
  // from than by an empty control between them and a working client.
  const [name, setName] = useState(`${marketplace}-client`);
  const [lifetime, setLifetime] = useState<Lifetime>(DEFAULT_LIFETIME);
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
    create.mutate(
      { name: trimmedName, expiresAt: expiryFor(lifetime) },
      {
        onSuccess: (issued) => {
          setCleartext(issued.token ?? null);
          setName("");
        },
        onError: (error) => toast.error(error.message),
      },
    );
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
          {serving ? null : <HeldNotice marketplace={marketplace} />}
          <form onSubmit={onCreate} className="space-y-2">
            <div className="text-sm font-medium">1. Personal access token</div>
            {cleartext ? (
              <p className="text-xs text-muted-foreground">
                Token created — its value is filled into the snippets below and shown only while
                this wizard is open. Only a hash is stored.
              </p>
            ) : (
              <>
                <div className="flex flex-wrap items-end gap-3">
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
                  <SegmentedGroup
                    label="Expires"
                    value={lifetime}
                    options={LIFETIMES}
                    onChange={setLifetime}
                    describedBy="wizard-token-name-hint"
                  />
                  <Button type="submit" disabled={!canCreate}>
                    {create.isPending ? "Creating…" : "Create token"}
                  </Button>
                </div>
                {create.isError ? (
                  // The one rule this form cannot mirror is the server's lifetime cap, so the
                  // refusal has to be readable where the choice was made rather than only in a
                  // toast that has already gone by the time the reader looks for it.
                  <p role="alert" className="text-xs text-destructive">
                    {create.error.message}
                  </p>
                ) : null}
                <p id="wizard-token-name-hint" className="text-xs text-muted-foreground">
                  A name is required — it identifies the token in your token list. The lifetime
                  defaults to 30 days; this gateway may cap it lower, in which case a longer choice
                  is refused. Already have a token? Leave this and put its value where the snippets
                  say {TOKEN_PLACEHOLDER}; existing token values are never shown again.
                </p>
              </>
            )}
          </form>
          <Snippet
            lead
            title="Store the credential — run this first"
            command={`printf 'protocol=${protocol.replace(":", "")}\\nhost=${host}\\nusername=token\\npassword=${token}\\n' | git credential approve`}
            copyLabel="Copy credential command"
            testId="wizard-credential-config"
          />
          <Snippet
            title="2. Add the marketplace to Claude Code"
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
          <Button variant="outline" onClick={onClose}>
            Done
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { Check, Copy } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import {
  useCreateWebhookSubscriber,
  useDeleteWebhookSubscriber,
  useWebhookDeliveries,
  useWebhookEvents,
  useWebhookSubscribers,
  type CreatedSubscriber,
} from "@/api/queries";
import { GATEWAY_NAME_HINT, isAbsoluteUrl, isValidGatewayName } from "@/lib/form-rules";
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
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { Label } from "@/components/ui/label";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

export function SubscriberSecretDialog({
  created,
  onClose,
}: {
  created: CreatedSubscriber;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(created.secret ?? "");
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
          <DialogTitle>Subscriber '{created.name}' created</DialogTitle>
          <DialogDescription>
            This signing secret is shown exactly once — copy it now. Verify each delivery by
            recomputing HMAC-SHA256 over the request body.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          <Label htmlFor="webhook-secret-box">Signing secret</Label>
          <div id="webhook-secret-box" className="flex items-center gap-2 rounded-md border bg-muted p-3">
            <code data-testid="webhook-secret" className="flex-1 break-all text-sm">
              {created.secret}
            </code>
            <Button
              variant="outline"
              size="icon"
              aria-label="Copy signing secret to clipboard"
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

function deliveryBadge(state: string | undefined) {
  if (state === "delivered") return <Badge>delivered</Badge>;
  if (state === "failed") return <Badge variant="destructive">failed</Badge>;
  return <Badge variant="secondary">{state ?? "pending"}</Badge>;
}

/**
 * Webhook administration: registered subscribers with their event filters, and the
 * recent delivery attempts with state, attempt count, and last response.
 *
 * @Requirements GW_0026
 */
/** Every event selected is the wildcard, not an enumeration: a filter written as `*` keeps
 *  receiving events added to the registry after the subscriber was registered. */
export const ALL_EVENTS = "*";

function toWireFilter(selected: Set<string>, registry: string[]): string {
  return selected.size === registry.length ? ALL_EVENTS : registry.filter((e) => selected.has(e)).join(",");
}

/**
 * The event filter, composed by selection from the server's registry rather than typed. The
 * type-ahead narrows the list only — it never selects or deselects, so filtering the view can
 * not silently change what gets submitted.
 */
function EventFilterField({
  registry,
  selected,
  onSelectedChange,
}: {
  registry: string[];
  selected: Set<string>;
  onSelectedChange: (next: Set<string>) => void;
}) {
  const [needle, setNeedle] = useState("");
  const shown = registry.filter((event) => event.includes(needle.trim().toLowerCase()));
  const allSelected = registry.length > 0 && selected.size === registry.length;

  const toggle = (event: string, checked: boolean) => {
    const next = new Set(selected);
    if (checked) {
      next.add(event);
    } else {
      next.delete(event);
    }
    onSelectedChange(next);
  };

  return (
    <div className="space-y-2">
      <Label htmlFor="subscriber-events-filter">Events</Label>
      <Input
        id="subscriber-events-filter"
        value={needle}
        onChange={(event) => setNeedle(event.target.value)}
        autoComplete="off"
        placeholder="Filter events…"
        aria-describedby="subscriber-form-hint"
      />
      <div className="max-h-44 space-y-1 overflow-y-auto rounded-md border p-2">
        <label className="flex items-center gap-2 text-sm font-medium">
          <Checkbox
            checked={allSelected}
            onCheckedChange={(checked) => onSelectedChange(checked ? new Set(registry) : new Set())}
          />
          All events
        </label>
        <Separator />
        {shown.length === 0 ? (
          <p className="px-1 py-2 text-xs text-muted-foreground">No event matches that text.</p>
        ) : (
          shown.map((event) => (
            <label key={event} className="flex items-center gap-2 font-mono text-xs">
              <Checkbox
                checked={selected.has(event)}
                onCheckedChange={(checked) => toggle(event, Boolean(checked))}
              />
              {event}
            </label>
          ))
        )}
      </div>
    </div>
  );
}

/**
 * A stored filter, read back against the registry. An event name the gateway no longer emits is
 * called out rather than shown as an ordinary filter: it matches nothing, so the subscriber looks
 * healthy while receiving less than its owner thinks — the failure this whole field exists to stop.
 */
function StoredFilter({ filter, registry }: { filter: string | undefined; registry: string[] }) {
  if (!filter || filter === ALL_EVENTS) {
    return <span className="rounded-md border bg-muted px-2 py-0.5 text-xs">all events</span>;
  }
  const names = filter.split(",").map((name) => name.trim()).filter(Boolean);
  // Until the registry loads there is nothing to check against, so nothing is called unknown.
  const unknown = registry.length === 0 ? [] : names.filter((name) => !registry.includes(name));
  return (
    <span className="space-x-1">
      <span className="rounded-md border bg-muted px-2 py-0.5 text-xs">{filter}</span>
      {unknown.length > 0 ? (
        <Badge variant="destructive" title={`Not emitted by this gateway: ${unknown.join(", ")}`}>
          {unknown.length === 1 ? "unknown event" : "unknown events"}
        </Badge>
      ) : null}
    </span>
  );
}

export function WebhooksPage() {
  const subscribers = useWebhookSubscribers();
  const deliveries = useWebhookDeliveries();
  const create = useCreateWebhookSubscriber();
  const remove = useDeleteWebhookSubscriber();
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const eventRegistry = useWebhookEvents();
  const registry = useMemo(() => eventRegistry.data ?? [], [eventRegistry.data]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  // Default to every event, matching the wildcard the field used to default to. Runs
  // when the registry arrives, and again if it changes.
  useEffect(() => setSelected(new Set(registry)), [registry]);
  const [created, setCreated] = useState<CreatedSubscriber | null>(null);

  const subscriberName = (id: number | undefined) =>
    subscribers.data?.find((subscriber) => subscriber.id === id)?.name ?? String(id ?? "");

  // Mirrors WebhookController: the name must match the gateway name pattern (422) and the
  // URL must parse with a scheme (400). Events are no longer in that category — the registry
  // is served, so the filter is composed from it rather than validated after the fact. Which
  // URL schemes are allowed remains configuration, and still surfaces as a ProblemDetail toast.
  const trimmedName = name.trim();
  const trimmedUrl = url.trim();
  const canCreate =
    isValidGatewayName(trimmedName) &&
    isAbsoluteUrl(trimmedUrl) &&
    selected.size > 0 &&
    !create.isPending;

  const onCreate = (event: React.FormEvent) => {
    event.preventDefault();
    if (!canCreate) {
      return;
    }
    create.mutate(
      { name: trimmedName, url: trimmedUrl, events: toWireFilter(selected, registry) },
      {
        onSuccess: (subscriber) => {
          setCreated(subscriber);
          setName("");
          setUrl("");
          setSelected(new Set(registry));
        },
        onError: (error) => toast.error(error.message),
      },
    );
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Webhooks</h1>
        <p className="text-sm text-muted-foreground">
          Snapshot lifecycle events are POSTed to each subscriber that filters for them, signed with
          HMAC-SHA256 and retried with backoff until delivered.
        </p>
      </div>

      <form onSubmit={onCreate} className="space-y-2">
        <div className="flex flex-wrap items-end gap-3">
          <div className="space-y-2">
            <Label htmlFor="subscriber-name">Subscriber name</Label>
            <Input
              id="subscriber-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              autoComplete="off"
              placeholder="ci-bot"
              aria-describedby="subscriber-form-hint"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="subscriber-url">Target URL</Label>
            <Input
              id="subscriber-url"
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              autoComplete="off"
              placeholder="https://ci.example.com/hooks/skills-gateway"
              aria-describedby="subscriber-form-hint"
            />
          </div>
          <EventFilterField registry={registry} selected={selected} onSelectedChange={setSelected} />
          <Button type="submit" disabled={!canCreate}>
            {create.isPending ? "Adding…" : "Add subscriber"}
          </Button>
        </div>
        <p id="subscriber-form-hint" className="text-xs text-muted-foreground">
          A name, a target URL and at least one event are required — Add subscriber enables
          once all three hold. {GATEWAY_NAME_HINT} With every event ticked the filter is stored
          as <code>*</code>, so events added to the gateway later are delivered too.
        </p>
      </form>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">Subscribers</h2>
        {subscribers.isLoading ? <p>Loading…</p> : null}
        {subscribers.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {subscribers.error.message}
          </p>
        ) : null}
        {subscribers.data?.length === 0 ? (
          <p className="text-sm text-muted-foreground">No subscribers yet.</p>
        ) : null}
        {subscribers.data && subscribers.data.length > 0 ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Target URL</TableHead>
                <TableHead>Events</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {subscribers.data.map((subscriber) => (
                <TableRow key={subscriber.id}>
                  <TableCell>{subscriber.name}</TableCell>
                  <TableCell className="break-all">{subscriber.url}</TableCell>
                  <TableCell>
                    <StoredFilter filter={subscriber.events} registry={registry} />
                  </TableCell>
                  <TableCell>
                    {subscriber.enabled ? <Badge>enabled</Badge> : <Badge variant="secondary">disabled</Badge>}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button
                      size="sm"
                      variant="destructive"
                      aria-label={`Delete subscriber ${subscriber.name}`}
                      disabled={remove.isPending}
                      onClick={() =>
                        remove.mutate(subscriber.id ?? 0, {
                          onSuccess: () => toast.success(`Subscriber '${subscriber.name}' deleted`),
                          onError: (error) => toast.error(error.message),
                        })
                      }
                    >
                      Delete
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : null}
      </section>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">Delivery attempts</h2>
        {deliveries.isLoading ? <p>Loading…</p> : null}
        {deliveries.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {deliveries.error.message}
          </p>
        ) : null}
        {deliveries.data?.length === 0 ? (
          <p className="text-sm text-muted-foreground">No deliveries yet.</p>
        ) : null}
        {deliveries.data && deliveries.data.length > 0 ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Event</TableHead>
                <TableHead>Subscriber</TableHead>
                <TableHead>State</TableHead>
                <TableHead>Attempts</TableHead>
                <TableHead>Last response</TableHead>
                <TableHead>Queued</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {deliveries.data.map((delivery) => (
                <TableRow key={delivery.id}>
                  <TableCell>{delivery.event}</TableCell>
                  <TableCell>{subscriberName(delivery.subscriberId)}</TableCell>
                  <TableCell>{deliveryBadge(delivery.state)}</TableCell>
                  <TableCell>{delivery.attempts}</TableCell>
                  <TableCell className="break-all text-sm text-muted-foreground">
                    {delivery.lastStatus ?? delivery.lastError ?? "—"}
                  </TableCell>
                  <TableCell>{delivery.createdAt}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : null}
      </section>

      {created ? <SubscriberSecretDialog created={created} onClose={() => setCreated(null)} /> : null}
    </div>
  );
}

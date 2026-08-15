import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import {
  useCreateWebhookSubscriber,
  useDeleteWebhookSubscriber,
  useWebhookDeliveries,
  useWebhookSubscribers,
  type CreatedSubscriber,
} from "@/api/queries";
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
export function WebhooksPage() {
  const subscribers = useWebhookSubscribers();
  const deliveries = useWebhookDeliveries();
  const create = useCreateWebhookSubscriber();
  const remove = useDeleteWebhookSubscriber();
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [events, setEvents] = useState("*");
  const [created, setCreated] = useState<CreatedSubscriber | null>(null);

  const subscriberName = (id: number | undefined) =>
    subscribers.data?.find((subscriber) => subscriber.id === id)?.name ?? String(id ?? "");

  const onCreate = (event: React.FormEvent) => {
    event.preventDefault();
    if (!name.trim() || !url.trim()) {
      toast.error("Name and URL are required");
      return;
    }
    create.mutate(
      { name: name.trim(), url: url.trim(), events: events.trim() || "*" },
      {
        onSuccess: (subscriber) => {
          setCreated(subscriber);
          setName("");
          setUrl("");
          setEvents("*");
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

      <form onSubmit={onCreate} className="flex flex-wrap items-end gap-3">
        <div className="space-y-2">
          <Label htmlFor="subscriber-name">Subscriber name</Label>
          <Input
            id="subscriber-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            autoComplete="off"
            placeholder="ci-bot"
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
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="subscriber-events">Events</Label>
          <Input
            id="subscriber-events"
            value={events}
            onChange={(event) => setEvents(event.target.value)}
            autoComplete="off"
            placeholder="snapshot.approved,snapshot.rejected"
          />
        </div>
        <Button type="submit" disabled={create.isPending}>
          {create.isPending ? "Adding…" : "Add subscriber"}
        </Button>
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
                    <span className="rounded-md border bg-muted px-2 py-0.5 text-xs">
                      {subscriber.events === "*" ? "all events" : subscriber.events}
                    </span>
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

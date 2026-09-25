import { Plus } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

/**
 * The frame both outbound integrations share: heading and description, the list, and — for an
 * administrator only — a control that opens the add form in a dialog. The server refuses a write
 * from anyone else regardless; this only declines to offer one.
 *
 * @Requirements GW_AUTH_0048
 */
export function IntegrationSection({
  title,
  description,
  addLabel,
  addTitle,
  addDescription,
  canAdd,
  adding,
  onAddingChange,
  form,
  children,
}: {
  title: string;
  description: ReactNode;
  /** The trigger's name, e.g. "New sink" — kept apart from the form's own submit label. */
  addLabel: string;
  addTitle: string;
  addDescription: ReactNode;
  canAdd: boolean;
  adding: boolean;
  onAddingChange: (open: boolean) => void;
  form: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
        <div className="min-w-0 flex-1 basis-80 space-y-1">
          <h1 className="text-2xl font-semibold">{title}</h1>
          <p className="text-sm text-muted-foreground">{description}</p>
        </div>
        {canAdd ? (
          <Button onClick={() => onAddingChange(true)}>
            <Plus className="size-4" aria-hidden />
            {addLabel}
          </Button>
        ) : null}
      </div>
      {children}
      <Dialog open={adding} onOpenChange={onAddingChange}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{addTitle}</DialogTitle>
            <DialogDescription>{addDescription}</DialogDescription>
          </DialogHeader>
          {form}
        </DialogContent>
      </Dialog>
    </div>
  );
}

import * as Dialog from "@radix-ui/react-dialog";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/** Bottom sheet built on Radix Dialog (focus trap, escape, scroll lock). */
export function Sheet(props: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  className?: string;
  dismissable?: boolean;
}) {
  return (
    <Dialog.Root open={props.open} onOpenChange={(o) => !o && props.dismissable !== false && props.onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 animate-fade bg-void/70" />
        <Dialog.Content
          aria-describedby={undefined}
          onInteractOutside={(e) => props.dismissable === false && e.preventDefault()}
          className={cn(
            "fixed inset-x-0 bottom-0 z-50 flex max-h-[88dvh] animate-rise flex-col rounded-t-sheet border-t border-line bg-surface pb-[max(env(safe-area-inset-bottom),12px)] outline-none",
            props.className,
          )}
        >
          <div className="mx-auto mb-1 mt-2.5 h-1 w-9 rounded-full bg-line" aria-hidden />
          <Dialog.Title className="px-5 pb-2 pt-2 text-lg font-semibold text-paper">{props.title}</Dialog.Title>
          {props.children}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

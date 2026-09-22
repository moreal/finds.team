import type { JSX } from "@solidjs/web";
import { createEffect, createSignal, createUniqueId, onCleanup, onSettled } from "solid-js";
import { tabbable } from "tabbable";

import "./kobalte.css";

export interface DialogProps {
  trigger: string;
  title: string;
  description?: string;
  closeLabel: string;
  children?: JSX.Element;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
}

/** Native fallback; see COMPATIBILITY.md#kobalte-alpha2-solid-rc9. */
export function Dialog(props: DialogProps): JSX.Element {
  const id = createUniqueId();
  const [internalOpen, setInternalOpen] = createSignal(false);
  const isOpen = () => props.open ?? internalOpen();
  let dialog!: HTMLDialogElement;
  let trigger!: HTMLButtonElement;

  function requestOpen(open: boolean) {
    if (props.open === undefined) setInternalOpen(open);
    props.onOpenChange?.(open);
  }

  function keepFocusInside(event: KeyboardEvent) {
    if (event.key !== "Tab" || event.defaultPrevented) return;
    const controls = tabbable(dialog);
    if (controls.length === 0) return;
    // Explicit traversal also respects the contract on macOS browsers whose
    // system preference excludes buttons from the ordinary Tab sequence.
    const original = document.activeElement;
    const current = controls.indexOf(original as HTMLElement);
    const start = current < 0 ? (event.shiftKey ? 0 : -1) : current;
    const direction = event.shiftKey ? -1 : 1;
    for (let offset = 1; offset <= controls.length; offset++) {
      const next = controls[(start + direction * offset + controls.length) % controls.length];
      if (next === original && controls.length > 1) continue;
      next.focus();
      if (document.activeElement === next) {
        event.preventDefault();
        return;
      }
    }
    // A focus listener can redirect focus, or a candidate can become hidden.
    // Leave the browser's default traversal available if no candidate accepts it.
  }

  createEffect(isOpen, (open) => {
    if (open && !dialog.open) dialog.showModal();
    else if (!open && dialog.open) {
      dialog.close();
      trigger.focus();
    }
  });
  onCleanup(() => {
    if (dialog?.open) {
      dialog.close();
      if (trigger?.isConnected) trigger.focus();
    }
  });

  return (
    <>
      <button ref={trigger} type="button" class="ui-dialog-trigger" aria-haspopup="dialog"
        aria-expanded={isOpen() ? "true" : "false"} aria-controls={id} onClick={() => requestOpen(true)}>
        {props.trigger}
      </button>
      <dialog ref={dialog} id={id} class="ui-dialog" aria-labelledby={`${id}-title`}
        aria-describedby={props.description ? `${id}-description` : undefined}
        onKeyDown={keepFocusInside}
        onCancel={(event) => { event.preventDefault(); requestOpen(false); }}
        onClose={() => {
          if (!dialog.open) {
            if (isOpen()) requestOpen(false);
            onSettled(() => {
              if (!dialog.isConnected) return;
              // Native method=dialog closes before notifying us. The parent can
              // decline that request without changing its controlled prop.
              if (isOpen()) {
                if (!dialog.open) dialog.showModal();
              } else {
                trigger.focus();
              }
            });
          }
        }}>
        <h2 id={`${id}-title`}>{props.title}</h2>
        <p id={`${id}-description`}>{props.description}</p>
        {props.children}
        <button type="button" onClick={() => requestOpen(false)}>{props.closeLabel}</button>
      </dialog>
    </>
  );
}

import type { JSX } from "@solidjs/web";
import { createEffect, createSignal, createUniqueId, onCleanup } from "solid-js";

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
    if (event.key !== "Tab") return;
    const controls = [...dialog.querySelectorAll<HTMLElement>("button, [href], input, select, textarea, [tabindex]")]
      .filter((element) => element.tabIndex >= 0 && !element.matches(":disabled")
        && !element.closest("[inert]") && element.getClientRects().length > 0);
    if (controls.length === 0) return;
    // Explicit traversal also respects the contract on macOS browsers whose
    // system preference excludes buttons from the ordinary Tab sequence.
    const current = controls.indexOf(document.activeElement as HTMLElement);
    const next = current < 0 ? (event.shiftKey ? controls.length - 1 : 0)
      : (current + (event.shiftKey ? -1 : 1) + controls.length) % controls.length;
    event.preventDefault();
    controls[next].focus();
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
            trigger.focus();
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

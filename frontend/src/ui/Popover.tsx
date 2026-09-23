import type { JSX } from "@solidjs/web";
import { createEffect, createSignal, createUniqueId, onCleanup, onSettled } from "solid-js";
import { tabbable } from "tabbable";
import { Button } from "./Button";
import "./composites.css";

export interface PopoverProps { trigger: string; title: string; children?: JSX.Element }

/** Non-modal disclosure; Tab remains in the ordinary document order. */
export function Popover(props: PopoverProps) {
  const id = createUniqueId();
  const [open, setOpen] = createSignal(false);
  let root!: HTMLDivElement;
  let trigger!: HTMLButtonElement;
  let panel!: HTMLDivElement;
  function close(restore = false) { setOpen(false); if (restore) trigger.focus(); }
  createEffect(open, visible => {
    if (!visible) return;
    const outside = (event: PointerEvent) => { if (!root.contains(event.target as Node)) close(); };
    document.addEventListener("pointerdown", outside);
    onCleanup(() => document.removeEventListener("pointerdown", outside));
  });
  return <div class="ui-popover" ref={root}
    onFocusOut={event => { if (event.relatedTarget && !root.contains(event.relatedTarget as Node)) close(); }}
    onKeyDown={event => { if (event.key === "Escape" && open()) { event.preventDefault(); event.stopPropagation(); close(true); } }}>
    <Button variant="secondary" ref={trigger} aria-haspopup="dialog" aria-expanded={open() ? "true" : "false"}
      aria-controls={open() ? id : undefined} onClick={() => {
        if (open()) close();
        else { setOpen(true); onSettled(() => (tabbable(panel)[0] ?? panel).focus()); }
      }}>{props.trigger}</Button>
    {open() && <div ref={panel} class="ui-popover-panel" role="dialog" id={id} aria-labelledby={`${id}-title`} tabindex={-1}>
      <h2 id={`${id}-title`}>{props.title}</h2>{props.children}
    </div>}
  </div>;
}

import { createSignal, createUniqueId, onCleanup } from "solid-js";
import { Button } from "./Button";
import "./composites.css";

export interface TooltipProps { label: string; content: string }

/** Supplementary help, available on keyboard focus and tap as well as hover. */
export function Tooltip(props: TooltipProps) {
  const id = createUniqueId();
  const [open, setOpen] = createSignal(false);
  let hovered = false;
  let focused = false;
  let dismissed = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const cancel = () => { clearTimeout(timer); timer = undefined; };
  function closeWhenInactive() {
    if (hovered || focused) return;
    cancel();
    setOpen(false);
    dismissed = false;
  }
  onCleanup(cancel);
  return <span class="ui-tooltip" onPointerEnter={event => {
    if (event.pointerType !== "mouse") return;
    hovered = true;
    cancel();
    if (!open() && !dismissed) timer = setTimeout(() => setOpen(true), 200);
  }} onPointerLeave={() => { hovered = false; cancel(); closeWhenInactive(); }}
    onFocusOut={() => { focused = false; closeWhenInactive(); }}
    onKeyDown={event => {
      if (event.key !== "Escape" || !open()) return;
      event.preventDefault();
      event.stopPropagation();
      cancel();
      dismissed = true;
      setOpen(false);
    }}>
    <Button variant="ghost" aria-describedby={open() ? id : undefined}
      onFocus={() => { focused = true; cancel(); if (!dismissed) setOpen(true); }}
      onClick={() => { cancel(); dismissed = false; setOpen(true); }}>{props.label}</Button>
    {open() && <span role="tooltip" id={id} class="ui-tooltip-content">{props.content}</span>}
  </span>;
}

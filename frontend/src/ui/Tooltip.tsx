import { createSignal, createUniqueId, onCleanup } from "solid-js";
import { Button } from "./Button";
import "./composites.css";

export interface TooltipProps { label: string; content: string }

/** Supplementary help, available on keyboard focus and tap as well as hover. */
export function Tooltip(props: TooltipProps) {
  const id = createUniqueId();
  const [open, setOpen] = createSignal(false);
  let timer: ReturnType<typeof setTimeout> | undefined;
  const cancel = () => { clearTimeout(timer); timer = undefined; };
  const close = () => { cancel(); setOpen(false); };
  onCleanup(cancel);
  return <span class="ui-tooltip" onPointerEnter={event => {
    if (event.pointerType === "mouse") { cancel(); timer = setTimeout(() => setOpen(true), 200); }
  }} onPointerLeave={close} onFocusOut={close}
    onKeyDown={event => { if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); close(); } }}>
    <Button variant="ghost" aria-describedby={open() ? id : undefined} onFocus={() => { cancel(); setOpen(true); }}
      onClick={() => { cancel(); setOpen(true); }}>{props.label}</Button>
    {open() && <span role="tooltip" id={id} class="ui-tooltip-content">{props.content}</span>}
  </span>;
}

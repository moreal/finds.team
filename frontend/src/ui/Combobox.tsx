import { createSignal, createUniqueId, For, onSettled } from "solid-js";
import { TextField } from "./TextField";
import "./composites.css";

export interface ComboboxOption { value: string; label: string }
export interface ComboboxProps {
  label: string;
  options: readonly ComboboxOption[];
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  name?: string;
  placeholder?: string;
}

/** Searchable, single selection. The parent owns the committed value. */
export function Combobox(props: ComboboxProps) {
  const id = createUniqueId();
  const [open, setOpen] = createSignal(false);
  const [query, setQuery] = createSignal<string | null>(null);
  const [active, setActive] = createSignal(-1);
  const expanded = () => open() && !props.disabled;
  const selectedLabel = () => props.options.find(option => option.value === props.value)?.label ?? "";
  const filtered = () => props.options.filter(option => query() === null || option.label.toLocaleLowerCase().includes(query()!.toLocaleLowerCase()));
  function close() { setOpen(false); setQuery(null); setActive(-1); }
  function select(option: ComboboxOption) { if (!props.disabled) props.onChange(option.value); close(); }
  function navigate(event: KeyboardEvent) {
    if (props.disabled) return;
    if (event.key === "Escape") { if (expanded()) { event.preventDefault(); event.stopPropagation(); } close(); }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      setOpen(true);
      const count = filtered().length;
      setActive(count ? (active() + (event.key === "ArrowDown" ? 1 : active() < 0 ? 0 : -1) + count) % count : -1);
      onSettled(() => document.getElementById(`${id}-option-${active()}`)?.scrollIntoView({ block: "nearest" }));
    }
    if (event.key === "Enter" && expanded() && filtered()[active()]) {
      event.preventDefault(); select(filtered()[active()]);
    }
  }
  return <div class="ui-combobox" onFocusOut={close}>
    <TextField label={props.label} role="combobox" autocomplete="off" placeholder={props.placeholder}
      disabled={props.disabled} value={query() ?? selectedLabel()} aria-autocomplete="list"
      aria-expanded={expanded() ? "true" : "false"} aria-controls={expanded() ? `${id}-list` : undefined}
      aria-activedescendant={expanded() && filtered()[active()] ? `${id}-option-${active()}` : undefined}
      onKeyDown={navigate} onInput={event => { setQuery(event.currentTarget.value); setActive(-1); setOpen(true); }}
      onClick={() => { setOpen(true); setActive(-1); }} />
    {props.name && <input type="hidden" name={props.name} value={props.value} disabled={props.disabled} />}
    {expanded() && <div class="ui-combobox-options" id={`${id}-list`} role="listbox" aria-label={props.label}>
      <For each={filtered()}>{(option, index) => <div id={`${id}-option-${index()}`} role="option"
        aria-selected={option.value === props.value ? "true" : "false"} data-active={active() === index()}
        onPointerDown={event => event.preventDefault()} onClick={() => select(option)}>{option.label}</div>}</For>
      {filtered().length === 0 && <p role="status">검색 결과가 없어요.</p>}
    </div>}
  </div>;
}

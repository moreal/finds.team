import type { JSX } from "@solidjs/web";
import { createUniqueId, For, onSettled } from "solid-js";

import "./kobalte.css";

export interface SelectProps<T> {
  label: string;
  name?: string;
  /** Values returned by getOptionValue must be unique within this list. */
  options: readonly T[];
  value: T;
  getOptionValue: (option: T) => string;
  getOptionLabel: (option: T) => string;
  onChange: (value: T) => void;
  disabled?: boolean;
  required?: boolean;
}

/** Native fallback; see COMPATIBILITY.md#kobalte-alpha2-solid-rc9. */
export function Select<T>(props: SelectProps<T>): JSX.Element {
  const id = createUniqueId();
  return (
    <div>
      <label for={id}>{props.label}</label>
      <select id={id} class="ui-select" name={props.name} disabled={props.disabled}
        required={props.required} value={props.getOptionValue(props.value)}
        onChange={(event) => {
          const select = event.currentTarget;
          const option = props.options.find((item) => props.getOptionValue(item) === select.value);
          if (option !== undefined) props.onChange(option);
          // The browser already changed its value. Once the parent's updates
          // settle, restore the authoritative prop even when it stayed unchanged.
          onSettled(() => {
            if (select.isConnected) select.value = props.getOptionValue(props.value);
          });
        }}>
        <For each={props.options}>{(option) => (
          <option value={props.getOptionValue(option)}
            selected={props.getOptionValue(option) === props.getOptionValue(props.value)}>
            {props.getOptionLabel(option)}
          </option>
        )}</For>
      </select>
    </div>
  );
}

import * as Primitive from "@kobalte/core/select";
import type { JSX } from "@solidjs/web";

import type { SelectProps } from "../../Select";
import "../../kobalte.css";

export function Select<T>(props: SelectProps<T>): JSX.Element {
  return (
    <Primitive.Root<T> options={[...props.options]} value={props.value}
      optionValue={props.getOptionValue} optionTextValue={props.getOptionLabel}
      onChange={(value) => { if (value != null) props.onChange(value); }}
      name={props.name} disabled={props.disabled} required={props.required}
      itemComponent={(item) => <Primitive.Item item={item.item}><Primitive.ItemLabel>{props.getOptionLabel(item.item.rawValue)}</Primitive.ItemLabel></Primitive.Item>}>
      <Primitive.Label>{props.label}</Primitive.Label>
      <Primitive.HiddenSelect />
      <Primitive.Trigger class="ui-select">
        <Primitive.Value<T>>{(state) => props.getOptionLabel(state.selectedOption())}</Primitive.Value>
      </Primitive.Trigger>
      <Primitive.Portal>
        <Primitive.Content><Primitive.Listbox /></Primitive.Content>
      </Primitive.Portal>
    </Primitive.Root>
  );
}

import * as Primitive from "@kobalte/core/dialog";
import type { JSX } from "@solidjs/web";

import type { DialogProps } from "../../Dialog";
import "../../kobalte.css";

export function Dialog(props: DialogProps): JSX.Element {
  return (
    <Primitive.Root open={props.open} onOpenChange={props.onOpenChange}>
      <Primitive.Trigger class="ui-dialog-trigger">{props.trigger}</Primitive.Trigger>
      <Primitive.Portal>
        <Primitive.Overlay class="ui-dialog-overlay" />
        <Primitive.Content class="ui-dialog">
          <Primitive.Title>{props.title}</Primitive.Title>
          <Primitive.Description>{props.description}</Primitive.Description>
          {props.children}
          <Primitive.CloseButton>{props.closeLabel}</Primitive.CloseButton>
        </Primitive.Content>
      </Primitive.Portal>
    </Primitive.Root>
  );
}

import { omit } from "solid-js";
import { Button, type ButtonProps } from "./Button";

export type IconButtonProps = Omit<ButtonProps, "aria-label"> & { label: string };

export function IconButton(props: IconButtonProps) {
  return <Button {...omit(props, "label", "class", "children")} aria-label={props.label} class={`ui-icon-button ${props.class ?? ""}`}>
    <span aria-hidden="true">{props.children}</span>
  </Button>;
}

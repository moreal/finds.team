import type { JSX } from "@solidjs/web";
import { omit } from "solid-js";
import "./foundations.css";

export type ButtonProps = JSX.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "ghost" | "danger" | "danger-confirm";
  size?: "regular" | "dense";
  loading?: boolean;
};

/** Use danger-confirm only inside the final destructive confirmation dialog. */
export function Button(props: ButtonProps) {
  return <button {...omit(props, "variant", "size", "loading", "class", "disabled", "type", "children")}
    type={props.type ?? "button"}
    class={`ui-button ${props.class ?? ""}`}
    data-variant={props.variant ?? "primary"} data-size={props.size ?? "regular"}
    disabled={props.disabled || props.loading} aria-busy={props.loading ? "true" : undefined}>
    {props.children}
  </button>;
}

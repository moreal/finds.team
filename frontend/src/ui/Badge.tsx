import type { JSX } from "@solidjs/web";
import { omit } from "solid-js";
import "./foundations.css";

export type BadgeProps = JSX.HTMLAttributes<HTMLSpanElement> & {
  tone?: "neutral" | "action" | "success" | "warning" | "danger";
};

export function Badge(props: BadgeProps) {
  return <span {...omit(props, "tone", "class", "children")} class={`ui-badge ${props.class ?? ""}`} data-tone={props.tone ?? "neutral"}>{props.children}</span>;
}

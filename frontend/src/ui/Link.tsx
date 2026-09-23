import type { JSX } from "@solidjs/web";
import { omit } from "solid-js";
import "./foundations.css";

export type LinkProps = JSX.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string };

export function Link(props: LinkProps) {
  return <a {...omit(props, "class", "rel", "children")}
    class={`ui-link ${props.class ?? ""}`}
    rel={props.target === "_blank" ? `${props.rel ?? ""} noopener noreferrer`.trim() : props.rel}>
    {props.children}
    {props.target === "_blank" && <span class="ui-visually-hidden"> (새 창)</span>}
  </a>;
}

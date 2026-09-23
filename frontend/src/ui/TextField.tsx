import type { JSX } from "@solidjs/web";
import { createUniqueId, omit } from "solid-js";
import "./foundations.css";

export type TextFieldProps = JSX.InputHTMLAttributes<HTMLInputElement> & {
  label: string;
  hint?: string;
  error?: string;
  size?: never;
};

export function TextField(props: TextFieldProps) {
  const generatedId = createUniqueId();
  const id = () => props.id ?? generatedId;
  const descriptions = () => [props["aria-describedby"], props.hint && `${id()}-hint`, props.error && `${id()}-error`].filter(Boolean).join(" ") || undefined;
  return <div class="ui-field">
    <label for={id()}>{props.label}</label>
    <input {...omit(props, "label", "hint", "error", "class", "id", "aria-describedby", "aria-invalid")}
      id={id()} class={`ui-input ${props.class ?? ""}`} aria-invalid={props.error ? "true" : props["aria-invalid"]}
      aria-describedby={descriptions()} />
    {props.hint && <p class="ui-field-hint" id={`${id()}-hint`}>{props.hint}</p>}
    {props.error && <p class="ui-field-error" id={`${id()}-error`}>{props.error}</p>}
  </div>;
}

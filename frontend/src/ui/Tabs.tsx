import type { JSX } from "@solidjs/web";
import { createUniqueId, For } from "solid-js";
import { Button } from "./Button";
import "./composites.css";

export interface TabItem { value: string; label: string; content: JSX.Element }
export interface TabsProps { label: string; tabs: readonly TabItem[]; value: string; onChange: (value: string) => void }

export function Tabs(props: TabsProps) {
  const id = createUniqueId();
  let list!: HTMLDivElement;
  function navigate(event: KeyboardEvent, index: number) {
    const count = props.tabs.length;
    const next = event.key === "Home" ? 0 : event.key === "End" ? count - 1 : event.key === "ArrowRight" ? (index + 1) % count : event.key === "ArrowLeft" ? (index - 1 + count) % count : -1;
    if (next < 0 || !count) return;
    event.preventDefault();
    props.onChange(props.tabs[next].value);
    list.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
  }
  return <div class="ui-tabs">
    <div ref={list} class="ui-tab-list" role="tablist" aria-label={props.label}>
      <For each={props.tabs}>{(tab, index) => <Button variant="ghost" role="tab" id={`${id}-tab-${index()}`}
        aria-selected={props.value === tab.value ? "true" : "false"} aria-controls={`${id}-panel-${index()}`}
        tabindex={props.value === tab.value ? 0 : -1} onClick={() => props.onChange(tab.value)}
        onKeyDown={event => navigate(event, index())}>{tab.label}</Button>}</For>
    </div>
    <For each={props.tabs}>{(tab, index) => <div role="tabpanel" id={`${id}-panel-${index()}`} aria-labelledby={`${id}-tab-${index()}`}
      tabindex={0} hidden={props.value !== tab.value}>{tab.content}</div>}</For>
  </div>;
}

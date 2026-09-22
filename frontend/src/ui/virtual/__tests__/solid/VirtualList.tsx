import { For, type JSX } from "@solidjs/web";
import { createVirtualizer } from "@tanstack/solid-virtual";
// Reproduction fixture only: SOLID-VIRTUAL3-SOLID-RC9 in COMPATIBILITY.md.

export interface VirtualListProps<T> {
  items: readonly T[];
  estimateSize: (index: number) => number;
  getKey: (item: T) => string | number;
  children: (item: T, index: number) => JSX.Element;
  enabled?: boolean;
}

export function VirtualList<T>(props: VirtualListProps<T>): JSX.Element {
  let container!: HTMLDivElement;
  const virtualizer = createVirtualizer<HTMLDivElement, HTMLDivElement>({
    get count() { return props.items.length; },
    getScrollElement: () => container,
    estimateSize: props.estimateSize,
    getItemKey: (index) => props.getKey(props.items[index]),
    enabled: false,
  });
  return <div ref={container} role="list" data-virtualized={virtualizer.options.enabled ? "true" : "false"}>
    <For each={props.items.slice(0, 20)}>{(item, index) => <div role="listitem" aria-posinset={index() + 1} aria-setsize={props.items.length}>
      {props.children(item, index())}
    </div>}</For>
  </div>;
}

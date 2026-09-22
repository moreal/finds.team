import { For, type JSX } from "@solidjs/web";
import { createEffect, createMemo, createSignal, onSettled } from "solid-js";
import {
  Virtualizer, defaultRangeExtractor, elementScroll, observeElementOffset, observeElementRect,
  type VirtualItem,
} from "@tanstack/virtual-core";

import "./virtual.css";

export interface VirtualListProps<T> {
  items: readonly T[];
  estimateSize: (index: number) => number;
  /** Stable, unique item identity, including across pagination and reordering. */
  getKey: (item: T) => string | number;
  children: (item: T, index: number) => JSX.Element;
  /** Caller has measured that this collection warrants virtualization. Defaults to false. */
  enabled?: boolean;
}

const INITIAL_PAGE = 20;
const OVERSCAN = 5;

/** Solid 2 lifecycle over virtual-core; see COMPATIBILITY.md. */
export function VirtualList<T>(props: VirtualListProps<T>): JSX.Element {
  let container!: HTMLDivElement;
  let canvas!: HTMLDivElement;
  let virtualizer: Virtualizer<HTMLDivElement, HTMLDivElement> | undefined;
  const [hydrated, setHydrated] = createSignal(false);
  const [threshold, setThreshold] = createSignal(Infinity);
  const [focused, setFocused] = createSignal<string | number | undefined>(undefined);
  const [layout, setLayout] = createSignal<{ rows: VirtualItem[]; size: number } | undefined>(undefined);
  const entries = createMemo(() => props.items.map((item, index) => ({ item, index, key: props.getKey(item) })));
  const byKey = createMemo(() => new Map(entries().map((entry) => [entry.key, entry])));
  const keys = createMemo(() => {
    const current = entries();
    if (!hydrated()) return current.slice(0, INITIAL_PAGE).map((entry) => entry.key);
    const measured = layout();
    // Inputs can shrink before the core's next effect publishes its new range.
    return measured
      ? measured.rows.map((row) => row.key as string | number).filter((key) => byKey().has(key))
      : current.map((entry) => entry.key);
  });

  function publish() {
    if (virtualizer?.options.enabled) {
      setLayout({ rows: virtualizer.getVirtualItems(), size: virtualizer.getTotalSize() });
    } else setLayout(undefined);
  }

  onSettled(() => {
    virtualizer = new Virtualizer({
      count: props.items.length,
      getScrollElement: () => container,
      estimateSize: (index) => props.estimateSize(index),
      observeElementRect,
      observeElementOffset,
      scrollToFn: elementScroll,
      enabled: false,
      onChange: publish,
    });
    const cleanup = virtualizer._didMount();
    // Measure actual row boxes, never estimates, for the activation threshold.
    const measureThreshold = () => {
      const rows = [...canvas.querySelectorAll<HTMLDivElement>("[data-index]")].slice(0, INITIAL_PAGE);
      const total = rows.reduce((sum, row) => sum + row.getBoundingClientRect().height, 0);
      const average = rows.length ? total / rows.length : 0;
      setThreshold(average > 0 && container.clientHeight > 0
        ? Math.max(INITIAL_PAGE, Math.ceil(container.clientHeight / average) * 3)
        : Infinity);
    };
    measureThreshold();
    const observer = new ResizeObserver(measureThreshold);
    observer.observe(container);
    observer.observe(canvas);
    for (const row of canvas.children) observer.observe(row);
    setHydrated(true);
    return () => { observer.disconnect(); cleanup(); virtualizer = undefined; };
  });

  createEffect(
    () => ({ ready: hydrated(), enabled: props.enabled === true, threshold: threshold(), entries: entries(), focused: focused() }),
    (state) => {
      if (!state.ready || !virtualizer) return;
      const focusedIndex = state.entries.findIndex((entry) => entry.key === state.focused);
      virtualizer.setOptions({
        ...virtualizer.options,
        count: state.entries.length,
        enabled: state.enabled && state.entries.length > state.threshold,
        getItemKey: (index) => state.entries[index].key,
        overscan: OVERSCAN,
        rangeExtractor: (range) => {
          const indexes = new Set(defaultRangeExtractor(range));
          // Preserve the SSR page at the top, including during activation.
          if ((virtualizer?.scrollOffset ?? 0) === 0) {
            for (let index = 0; index < Math.min(INITIAL_PAGE, range.count); index++) indexes.add(index);
          }
          // Keep focused controls and their keyboard neighbours mounted even if
          // the pointer scrolls away, then release them when focus leaves.
          if (focusedIndex >= 0) {
            for (let index = Math.max(0, focusedIndex - OVERSCAN); index <= Math.min(range.count - 1, focusedIndex + OVERSCAN); index++) indexes.add(index);
          }
          return [...indexes].sort((a, b) => a - b);
        },
      });
      virtualizer._willUpdate();
      publish();
    },
  );

  createEffect(layout, (measured) => {
    // CSSOM property writes are allowed by the application's strict CSP;
    // SSR and hydration never emit inline style attributes.
    canvas.style.height = measured ? `${measured.size}px` : "";
  });

  return <div ref={container} class="ui-virtual-list" role="list" data-virtualized={layout() ? "true" : "false"}
    onFocusOut={(event) => {
      if (!container.contains(event.relatedTarget as Node | null)) setFocused(undefined);
    }}>
    <div ref={canvas} class="ui-virtual-canvas">
      <For each={keys()}>{(key) => {
        let row!: HTMLDivElement;
        const entry = () => byKey().get(key)!;
        createEffect(() => ({ measured: layout(), index: entry().index }), ({ measured, index }) => {
          const position = measured?.rows.find((item) => item.index === index);
          row.style.transform = position ? `translateY(${position.start}px)` : "";
          if (measured) virtualizer?.measureElement(row);
        });
        return <div ref={row} class="ui-virtual-row" role="listitem" data-index={entry().index}
          aria-posinset={entry().index + 1} aria-setsize={props.items.length}
          onFocusIn={() => setFocused(key)}>
          {props.children(entry().item, entry().index)}
        </div>;
      }}</For>
    </div>
  </div>;
}

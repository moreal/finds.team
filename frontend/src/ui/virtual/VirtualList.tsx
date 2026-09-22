import { For, type JSX } from "@solidjs/web";
import { createEffect, createMemo, createSignal, onSettled, type Accessor } from "solid-js";
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
  /** Called once per mounted key; accessors keep row content and position live. */
  children: (item: Accessor<T>, index: Accessor<number>) => JSX.Element;
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
  let measureRows: (() => void) | undefined;
  let measurementFrame: number | undefined;
  const [hydrated, setHydrated] = createSignal(false);
  const [focused, setFocused] = createSignal<string | number | undefined>(undefined);
  const [layout, setLayout] = createSignal<{ rows: VirtualItem[]; size: number } | undefined>(undefined);
  const entries = createMemo(() => props.items.map((item, index) => ({ item, index, key: props.getKey(item) })));
  const [measurement, setMeasurement] = createSignal<{
    entries: ReturnType<typeof entries>; enabled: boolean; threshold: number;
  }>();
  const byKey = createMemo(() => new Map(entries().map((entry) => [entry.key, entry])));
  const visibleEntries = createMemo(() => {
    const current = entries();
    if (!hydrated()) return current.slice(0, INITIAL_PAGE);
    const measured = layout();
    // Inputs can shrink before the core's next effect publishes its new range.
    return measured
      ? measured.rows.flatMap((row) => {
        const entry = byKey().get(row.key as string | number);
        return entry ? [entry] : [];
      })
      : current;
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
      initialOffset: () => container.scrollTop,
      estimateSize: (index) => props.estimateSize(index),
      observeElementRect,
      observeElementOffset,
      scrollToFn: elementScroll,
      enabled: false,
      onChange: publish,
    });
    const cleanup = virtualizer._didMount();
    // Keep collection evidence when its first page leaves the virtual window.
    // Sampling only the current window makes tall/short collections oscillate.
    const sampleSizes = new Map<string | number, { item: T; size: number }>();
    const watchedRows = new Set<Element>();
    function measureThreshold() {
      const current = entries();
      const sample = current.slice(0, INITIAL_PAGE);
      const sampleKeys = new Set(sample.map((entry) => entry.key));
      const renderedSample = new Set<Element>();
      for (const key of sampleSizes.keys()) if (!sampleKeys.has(key)) sampleSizes.delete(key);
      for (const node of canvas.children) {
        const row = node as HTMLDivElement;
        const entry = current[Number(row.dataset.index)];
        if (entry && sampleKeys.has(entry.key)) {
          sampleSizes.set(entry.key, { item: entry.item, size: row.getBoundingClientRect().height });
          renderedSample.add(row);
          if (!watchedRows.has(row)) { observer.observe(row); watchedRows.add(row); }
        }
      }
      for (const row of watchedRows) {
        if (!renderedSample.has(row)) { observer.unobserve(row); watchedRows.delete(row); }
      }
      const sizes = sample.flatMap((entry) => {
        const known = sampleSizes.get(entry.key);
        return known && Object.is(known.item, entry.item) ? [known.size] : [];
      });
      // A new item object needs fresh DOM evidence, even under a retained key.
      if (sizes.length !== sample.length) return;
      const average = sizes.length ? sizes.reduce((sum, size) => sum + size, 0) / sizes.length : 0;
      const threshold = average > 0 && container.clientHeight > 0
        ? Math.max(INITIAL_PAGE, Math.ceil(container.clientHeight / average) * 3)
        : Infinity;
      const enabled = props.enabled === true;
      setMeasurement((previous) => previous?.entries === current && previous.enabled === enabled && previous.threshold === threshold
        ? previous : { entries: current, enabled, threshold });
    }
    const observer = new ResizeObserver(measureThreshold);
    observer.observe(container);
    observer.observe(canvas);
    measureRows = measureThreshold;
    measureThreshold();
    setHydrated(true);
    return () => {
      if (measurementFrame !== undefined) cancelAnimationFrame(measurementFrame);
      observer.disconnect(); cleanup(); virtualizer = undefined; measureRows = undefined;
    };
  });

  // Read collection/content geometry after the DOM's current updates finish.
  // Rendering a new window can refresh which sample nodes are observed, but
  // cached evidence for unchanged sample items survives ordinary scrolling.
  createEffect(
    () => ({ ready: hydrated(), entries: entries(), enabled: props.enabled, visible: visibleEntries() }),
    ({ ready }) => {
      if (!ready) return;
      if (measurementFrame !== undefined) cancelAnimationFrame(measurementFrame);
      measurementFrame = requestAnimationFrame(() => { measurementFrame = undefined; measureRows?.(); });
    },
  );

  createEffect(
    () => ({ ready: hydrated(), enabled: props.enabled === true, measurement: measurement(), entries: entries(), focused: focused() }),
    (state) => {
      if (!state.ready || !virtualizer) return;
      const focusedIndex = state.entries.findIndex((entry) => entry.key === state.focused);
      const wasEnabled = virtualizer.options.enabled;
      const fresh = state.measurement?.entries === state.entries && state.measurement.enabled === state.enabled;
      // Pending evidence may retain an already-active mode, but may not start
      // activation. Fresh collection/content measurements can change either way.
      const enabled = state.enabled && state.entries.length > INITIAL_PAGE
        && (fresh ? state.entries.length > state.measurement!.threshold : wasEnabled);
      // Seed every already-rendered row before switching to estimates. This
      // preserves the visible item and its intra-row offset at any scroll depth.
      const measurements = enabled && !wasEnabled
        ? [...canvas.children].map((node) => {
          const row = node as HTMLDivElement;
          const index = Number(row.dataset.index);
          const start = row.offsetTop;
          const size = row.getBoundingClientRect().height;
          return { index, key: state.entries[index].key, start, size, end: start + size, lane: 0 };
        })
        : virtualizer.options.initialMeasurementsCache;
      const top = container.getBoundingClientRect().top + container.clientTop;
      const anchorRow = wasEnabled && !enabled
        ? [...canvas.children].find((node) => node.getBoundingClientRect().bottom > top) as HTMLDivElement | undefined
        : undefined;
      const anchor = anchorRow && {
        key: state.entries[Number(anchorRow.dataset.index)]?.key,
        offset: anchorRow.getBoundingClientRect().top - top,
      };
      virtualizer.setOptions({
        ...virtualizer.options,
        count: state.entries.length,
        enabled,
        initialMeasurementsCache: measurements,
        getItemKey: (index) => state.entries[index].key,
        overscan: OVERSCAN,
        rangeExtractor: (range) => {
          const indexes = new Set(defaultRangeExtractor(range));
          // Preserve the SSR page at the top, including during activation.
          if ((virtualizer?.scrollOffset ?? 0) === 0 || !fresh) {
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
      if (anchor) onSettled(() => {
        if (virtualizer?.options.enabled) return;
        const index = entries().findIndex((entry) => entry.key === anchor.key);
        const row = canvas.querySelector<HTMLDivElement>(`[data-index="${index}"]`);
        // The rows are back in flow, but their effect-owned transforms may not
        // have cleared yet. offsetTop reads the untransformed flow position.
        if (row) container.scrollTop += row.offsetTop + canvas.getBoundingClientRect().top
          - container.getBoundingClientRect().top - container.clientTop - anchor.offset;
      });
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
      <For each={visibleEntries()} keyed={(entry) => entry.key}>{(entry) => {
        let row!: HTMLDivElement;
        // For owns the entry accessor until its row scope is disposed. It does
        // not look up a key that may already have disappeared from the input.
        const item = createMemo(() => entry().item);
        const index = createMemo(() => entry().index);
        const content = props.children(item, index);
        createEffect(() => ({ measured: layout(), index: index() }), ({ measured, index }) => {
          const position = measured?.rows.find((item) => item.index === index);
          row.style.transform = position ? `translateY(${position.start}px)` : "";
          if (measured) virtualizer?.measureElement(row);
        });
        return <div ref={row} class="ui-virtual-row" role="listitem" data-index={index()}
          aria-posinset={index() + 1} aria-setsize={props.items.length}
          onFocusIn={() => setFocused(entry().key)}>
          {content}
        </div>;
      }}</For>
    </div>
  </div>;
}

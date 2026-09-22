import { createSignal, onSettled } from "solid-js";
import { VirtualList } from "../../src/ui/virtual/VirtualList";

function StatefulRow(props: { id: number; index: number; label: string }) {
  const [edits, setEdits] = createSignal(0);
  return <button class="fixture-row" data-state-row={props.id} data-position={props.index} data-label={props.label}
    onClick={() => setEdits((value) => value + 1)}>State {props.id}: {edits()}</button>;
}

function RemovalRow(props: { id: number; onMounted: (delta: number) => void }) {
  onSettled(() => {
    props.onMounted(1);
    return () => props.onMounted(-1);
  });
  return <button class="fixture-row" data-removal-row={props.id}>Mutable {props.id}</button>;
}

function ReconciliationRows(props: { active: boolean }) {
  const original = Array.from({ length: props.active ? 30 : 3 }, (_, id) => ({ id }));
  const [rows, setRows] = createSignal(original);
  const [mounted, setMounted] = createSignal(0);
  return <section aria-label={props.active ? "Active mutations" : "Disabled mutations"}>
    <button onClick={() => setRows((items) => items.filter((item) => item.id !== 1))}>Remove middle</button>
    <button onClick={() => setRows(original.map((item) => ({ id: item.id + 1000 })))}>Replace rows</button>
    <button onClick={() => setRows([])}>Clear rows</button>
    <button onClick={() => setRows(original)}>Restore rows</button>
    <output aria-label="Mounted rows">{mounted()}</output>
    <VirtualList items={rows()} getKey={(item) => item.id} estimateSize={() => 60} enabled={props.active}>
      {(item) => <RemovalRow id={item().id} onMounted={(delta) => setMounted((value) => value + delta)} />}
    </VirtualList>
  </section>;
}

function FreshMeasurementRows() {
  const makeRows = (count: number, compact: boolean) => Array.from({ length: count }, (_, id) => ({ id, compact }));
  const [rows, setRows] = createSignal(makeRows(3, false));
  return <section aria-label="Fresh measurements">
    <button onClick={() => setRows(makeRows(25, true))}>Grow compact sample</button>
    <button onClick={() => setRows(makeRows(25, false))}>Expand sample</button>
    <button onClick={() => setRows(makeRows(25, true))}>Compact sample</button>
    <VirtualList items={rows()} getKey={(item) => item.id} estimateSize={() => 60} enabled>
      {(item) => <div class={item().compact ? "fixture-compact" : "fixture-row"}>Sample {item().id}</div>}
    </VirtualList>
  </section>;
}

function ResizeRow(props: { id: number; onSampleMount: () => void }) {
  onSettled(() => { if (props.id < 20) props.onSampleMount(); });
  return <div class="fixture-row" data-resize-row={props.id}>Resize {props.id}</div>;
}

function OffscreenResizeRows() {
  const items = Array.from({ length: 200 }, (_, id) => ({ id }));
  const [compact, setCompact] = createSignal(false);
  const [sampleMounts, setSampleMounts] = createSignal(0);
  return <section aria-label="Offscreen resizing" class={compact() ? "fixture-condensed" : ""}>
    <button onClick={() => setCompact(true)}>Shrink all boxes</button>
    <button onClick={() => setCompact(false)}>Expand all boxes</button>
    <output aria-label="Sample mounts">{sampleMounts()}</output>
    <VirtualList items={items} getKey={(item) => item.id} estimateSize={() => 80} enabled>
      {(item) => <ResizeRow id={item().id} onSampleMount={() => setSampleMounts((count) => count + 1)} />}
    </VirtualList>
  </section>;
}

function WidthSensitiveRow(props: { id: number; onSampleMount: () => void }) {
  onSettled(() => { if (props.id < 20) props.onSampleMount(); });
  return <div class={props.id < 20 ? "fixture-width-row" : "fixture-width-tail"} data-width-row={props.id}>
    {props.id < 20 ? `Container width sample ${props.id}: compatible responsive geometry` : `Width tail ${props.id}`}
  </div>;
}

function ContainerWidthRows() {
  const items = Array.from({ length: 200 }, (_, id) => ({ id }));
  const [narrow, setNarrow] = createSignal(false);
  const [sampleMounts, setSampleMounts] = createSignal(0);
  return <section aria-label="Container width invalidation"
    class={`fixture-width-case${narrow() ? " fixture-width-narrow" : ""}`}>
    <button onClick={() => setNarrow((value) => !value)}>{narrow() ? "Widen container" : "Narrow container"}</button>
    <output aria-label="Width sample mounts">{sampleMounts()}</output>
    <VirtualList items={items} getKey={(item) => item.id} estimateSize={() => 40} enabled>
      {(item) => <WidthSensitiveRow id={item().id} onSampleMount={() => setSampleMounts((count) => count + 1)} />}
    </VirtualList>
  </section>;
}

export function VirtualLists() {
  const items = Array.from({ length: 200 }, (_, id) => ({ id, label: `Row ${id}` }));
  const [enabled, setEnabled] = createSignal(false);
  const [count, setCount] = createSignal(3);
  const [loaded, setLoaded] = createSignal(false);
  const [expanded, setExpanded] = createSignal(false);
  const [stateful, setStateful] = createSignal(items.slice(0, 3));
  const [initialEnabled, setInitialEnabled] = createSignal(true);
  return <main>
    <button onClick={() => setEnabled((value) => !value)}>Toggle virtualization</button>
    <button onClick={() => setCount(200)}>Grow short list</button>
    <button onClick={() => setCount(3)}>Shrink short list</button>
    <button onClick={() => setLoaded(true)}>Load empty list</button>
    <button onClick={() => setExpanded(true)}>Expand compact rows</button>
    <button onClick={() => setStateful(items)}>Append stateful rows</button>
    <button onClick={() => setStateful([...items.slice(0, 3).reverse(), ...items.slice(3)])}>Reorder stateful rows</button>
    <button onClick={() => setStateful((rows) => rows.map((row) => ({ ...row, label: `Updated ${row.id}` })))}>Refresh stateful rows</button>
    <button onClick={() => setInitialEnabled((value) => !value)}>Toggle initially enabled rows</button>
    <section aria-label="Variable rows">
      <VirtualList items={items} getKey={(item) => item.id} estimateSize={() => 70} enabled={enabled()}>
        {(item) => <button class={`fixture-row fixture-height-${item().id % 3}`} data-row={item().id}>{item().label}</button>}
      </VirtualList>
    </section>
    <section aria-label="Short rows">
      <VirtualList items={items.slice(0, count())} getKey={(item) => item.id} estimateSize={() => 70} enabled>
        {(item) => <button class="fixture-row">Short {item().id}</button>}
      </VirtualList>
    </section>
    <section aria-label="Initially enabled">
      <VirtualList items={items} getKey={(item) => item.id} estimateSize={() => 70} enabled={initialEnabled()}>
        {(item) => <button class="fixture-row" data-row={item().id}>Initial {item().id}</button>}
      </VirtualList>
    </section>
    <section aria-label="Initially empty">
      <VirtualList items={loaded() ? items : []} getKey={(item) => item.id} estimateSize={() => 70} enabled>
        {(item) => <button class="fixture-row">Loaded {item().id}</button>}
      </VirtualList>
    </section>
    <section aria-label="Compact rows">
      <VirtualList items={items.slice(0, 25)} getKey={(item) => item.id} estimateSize={() => 700} enabled>
        {(item) => <div class={expanded() ? "fixture-row" : "fixture-compact"}>Compact {item().id}</div>}
      </VirtualList>
    </section>
    <section aria-label="Stateful rows">
      <VirtualList items={stateful()} getKey={(item) => item.id} estimateSize={() => 60} enabled>
        {(item, index) => <StatefulRow id={item().id} index={index()} label={item().label} />}
      </VirtualList>
    </section>
    <section aria-label="Heterogeneous rows">
      <VirtualList items={items} getKey={(item) => item.id} estimateSize={() => 20} enabled>
        {(item) => <div class={item().id < 20 ? "fixture-row" : "fixture-compact"} data-heterogeneous-row={item().id}>Mixed {item().id}</div>}
      </VirtualList>
    </section>
    <ReconciliationRows active={false} />
    <ReconciliationRows active />
    <FreshMeasurementRows />
    <OffscreenResizeRows />
    <ContainerWidthRows />
  </main>;
}

import { createSignal } from "solid-js";
import { VirtualList } from "../../src/ui/virtual/VirtualList";

export function VirtualLists() {
  const items = Array.from({ length: 200 }, (_, id) => ({ id, label: `Row ${id}` }));
  const [enabled, setEnabled] = createSignal(false);
  const [count, setCount] = createSignal(3);
  const [loaded, setLoaded] = createSignal(false);
  const [expanded, setExpanded] = createSignal(false);
  return <main>
    <button onClick={() => setEnabled((value) => !value)}>Toggle virtualization</button>
    <button onClick={() => setCount(200)}>Grow short list</button>
    <button onClick={() => setCount(3)}>Shrink short list</button>
    <button onClick={() => setLoaded(true)}>Load empty list</button>
    <button onClick={() => setExpanded(true)}>Expand compact rows</button>
    <section aria-label="Variable rows">
      <VirtualList items={items} getKey={(item) => item.id} estimateSize={() => 70} enabled={enabled()}>
        {(item) => <button class={`fixture-row fixture-height-${item.id % 3}`} data-row={item.id}>{item.label}</button>}
      </VirtualList>
    </section>
    <section aria-label="Short rows">
      <VirtualList items={items.slice(0, count())} getKey={(item) => item.id} estimateSize={() => 70} enabled>
        {(item) => <button class="fixture-row">Short {item.id}</button>}
      </VirtualList>
    </section>
    <section aria-label="Initially enabled">
      <VirtualList items={items} getKey={(item) => item.id} estimateSize={() => 70} enabled>
        {(item) => <button class="fixture-row">Initial {item.id}</button>}
      </VirtualList>
    </section>
    <section aria-label="Initially empty">
      <VirtualList items={loaded() ? items : []} getKey={(item) => item.id} estimateSize={() => 70} enabled>
        {(item) => <button class="fixture-row">Loaded {item.id}</button>}
      </VirtualList>
    </section>
    <section aria-label="Compact rows">
      <VirtualList items={items.slice(0, 25)} getKey={(item) => item.id} estimateSize={() => 700} enabled>
        {(item) => <div class={expanded() ? "fixture-row" : "fixture-compact"}>Compact {item.id}</div>}
      </VirtualList>
    </section>
  </main>;
}

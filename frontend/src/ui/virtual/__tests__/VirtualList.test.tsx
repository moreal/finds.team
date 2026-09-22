import { renderToString } from "@solidjs/web";
import { JSDOM } from "jsdom";
import { expect, it, vi } from "vitest";

import { VirtualList } from "../VirtualList";

const items = Array.from({ length: 200 }, (_, id) => ({ id, label: `Row ${id}` }));

it("renders a deterministic bounded first page in normal flow even when enabled", () => {
  const estimateSize = vi.fn(() => 80);
  const render = () => renderToString(() => (
    <VirtualList items={items} estimateSize={estimateSize} getKey={(item) => item.id} enabled>
      {(item) => <button>{item.label}</button>}
    </VirtualList>
  ), { manifest: {} });
  const html = render();
  expect(render()).toBe(html);
  const document = new JSDOM(html).window.document;
  const rows = [...document.querySelectorAll("[role=listitem]")];
  expect(rows.length).toBeGreaterThan(0);
  expect(rows.length).toBeLessThanOrEqual(30);
  expect(rows.map((row) => row.textContent)).toEqual(items.slice(0, rows.length).map((item) => item.label));
  expect(document.querySelector("[style]")).toBeNull();
  expect(document.querySelector("[data-virtualized=true]")).toBeNull();
  expect(estimateSize).not.toHaveBeenCalled();
  expect(rows[0].getAttribute("aria-posinset")).toBe("1");
  expect(rows[0].getAttribute("aria-setsize")).toBe("200");
});

it("renders every item in a short page and accepts an empty page", () => {
  const render = (values: typeof items) => new JSDOM(renderToString(() => (
    <VirtualList items={values} estimateSize={() => 80} getKey={(item) => item.id}>
      {(item) => <button>{item.label}</button>}
    </VirtualList>
  ), { manifest: {} })).window.document;
  expect(render(items.slice(0, 3)).querySelectorAll("button")).toHaveLength(3);
  expect(render([]).querySelectorAll("[role=listitem]")).toHaveLength(0);
});

import { renderToString } from "@solidjs/web";
import { JSDOM } from "jsdom";
import { expect, it } from "vitest";

import { Dialog } from "../Dialog";

it("renders a named dialog trigger without exposing closed content to keyboard users", () => {
  const html = renderToString(() => (
    <Dialog trigger="Edit preferences" title="Preferences" closeLabel="Close preferences">
      <button type="button">Save preferences</button>
    </Dialog>
  ), { manifest: {} });
  const document = new JSDOM(html).window.document;
  const trigger = document.querySelector("button")!;
  expect(trigger.textContent).toBe("Edit preferences");
  expect(trigger.type).toBe("button");
  expect(trigger.getAttribute("aria-haspopup")).toBe("dialog");
  expect(trigger.getAttribute("aria-expanded")).toBe("false");
  expect(document.querySelector("dialog[open], [role=dialog]")).toBeNull();
});

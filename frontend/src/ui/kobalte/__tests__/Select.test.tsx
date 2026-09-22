import { renderToString } from "@solidjs/web";
import { JSDOM } from "jsdom";
import { expect, it } from "vitest";

import { Select } from "../Select";

it("server-renders the selected object as a labeled successful form control", () => {
  const options = [{ id: "all", label: "All roles" }, { id: "engineering", label: "Engineering" }];
  const html = renderToString(() => (
    <form>
      <Select label="Role" name="role" options={options} value={options[1]}
        getOptionValue={(option) => option.id} getOptionLabel={(option) => option.label}
        onChange={() => undefined} />
    </form>
  ), { manifest: {} });
  const window = new JSDOM(html).window;
  const select = window.document.querySelector("select")!;
  expect(select).not.toBeNull();
  expect(new window.FormData(window.document.querySelector("form")!).get("role")).toBe("engineering");
  const control = window.document.querySelector("button[aria-haspopup=listbox]") ?? select;
  const label = select.labels?.[0] ?? window.document.getElementById(control.getAttribute("aria-labelledby")?.split(" ")[0] ?? "");
  expect(label).not.toBeNull();
  expect(label?.textContent).toBe("Role");
});

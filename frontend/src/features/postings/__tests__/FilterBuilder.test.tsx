import { renderToString } from "@solidjs/web";
import { JSDOM } from "jsdom";
import { expect, it } from "vitest";
import { FilterBuilder, jobSearchFromForm } from "../FilterBuilder";

it("renders shareable filters as a labeled GET form with skill exclusion and level preserved", () => {
  const html = renderToString(() => <FilterBuilder state={{ text: "Kotlin", skills: [{ slug: "java", level: "PREFERRED", exclude: true }], remote: "REMOTE" }} />, { manifest: {} });
  const document = new JSDOM(html).window.document;
  const form = document.querySelector("form")!;
  expect(form.method).toBe("get");
  expect(form.getAttribute("action")).toBe("/jobs");
  expect(document.querySelector<HTMLInputElement>('[name="q"]')?.value).toBe("Kotlin");
  expect(document.querySelector<HTMLInputElement>('[name="skill"]')?.value).toBe("-java:preferred");
  for (const control of form.querySelectorAll("input, select")) {
    expect(document.querySelector(`label[for="${control.id}"]`)).not.toBeNull();
  }
});

it("submitting controls removes empty values and keeps repeated skills", () => {
  const data = new FormData();
  data.set("q", "  platform  ");
  data.set("remote", "");
  data.append("skill", "kotlin:required");
  data.append("skill", "-java");
  expect(jobSearchFromForm(data)).toBe("?q=platform&skill=kotlin%3Arequired&skill=-java");
});

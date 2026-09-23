import { renderToString } from "@solidjs/web";
import { JSDOM } from "jsdom";
import { expect, it, vi } from "vitest";
import { DetailError } from "../DetailRoute";

vi.mock("@tanstack/solid-router", () => ({ useRouter: () => ({ invalidate: () => Promise.resolve() }) }));

it("unexpected route boundary renders the logged diagnostic without exposing error details", () => {
  const log = vi.spyOn(console, "error").mockImplementation(() => {});
  try {
    const error = new Error("secret provider stack and credentials");
    const document = new JSDOM(renderToString(() => <DetailError {...{ error }} />, { manifest: {} })).window.document;
    const diagnostic = document.querySelector("code")?.textContent;
    expect(diagnostic).toBeTruthy();
    expect(log).toHaveBeenCalledWith(expect.any(String), { correlationId: diagnostic });
    expect(document.body.textContent).not.toContain("secret provider");
    expect(document.querySelector("button")?.textContent).toBe("다시 시도");
  } finally { log.mockRestore(); }
});

import { randomBytes } from "node:crypto";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { build } from "vite";
import solid from "vite-plugin-solid";

import { createContentSecurityPolicy } from "../../src/security/csp.ts";

async function bundle(server: boolean, fixture = "controls") {
  const output = await build({
    configFile: false,
    root: resolve(import.meta.dirname, "../.."),
    plugins: [solid({ ssr: true })],
    logLevel: "warn",
    resolve: { alias: {
      "solid-js/web": "@solidjs/web",
      ...(process.env.SOLID_VIRTUAL_COMPATIBILITY === "1" ? {
        "../../src/ui/virtual/VirtualList": resolve(import.meta.dirname, "../../src/ui/virtual/__tests__/solid/VirtualList.tsx"),
      } : {}),
      ...(process.env.KOBALTE_COMPATIBILITY === "1" ? {
        "./kobalte/Dialog": resolve(import.meta.dirname, "../../src/ui/kobalte/__tests__/alpha/Dialog.tsx"),
        "./kobalte/Select": resolve(import.meta.dirname, "../../src/ui/kobalte/__tests__/alpha/Select.tsx"),
        "../../src/ui/kobalte/Dialog": resolve(import.meta.dirname, "../../src/ui/kobalte/__tests__/alpha/Dialog.tsx"),
        "../../src/ui/kobalte/Select": resolve(import.meta.dirname, "../../src/ui/kobalte/__tests__/alpha/Select.tsx"),
      } : {}),
    } },
    define: { "process.env.NODE_ENV": '"production"' },
    ssr: { noExternal: true },
    build: {
      write: false,
      minify: false,
      ...(server
        ? { ssr: resolve(import.meta.dirname, `${fixture}-server.tsx`) }
        : { lib: { entry: resolve(import.meta.dirname, `${fixture}-client.tsx`), formats: ["es" as const] } }),
    },
  });
  if ("close" in output) throw new Error("Expected one-shot build");
  const code = (Array.isArray(output) ? output[0] : output).output.find((chunk) => chunk.type === "chunk");
  if (code?.type !== "chunk") throw new Error("Expected JavaScript bundle");
  return code.code;
}

const [serverCode, clientCode] = await Promise.all([bundle(true), bundle(false)]);
const { renderControls } = await import(`data:text/javascript;base64,${Buffer.from(serverCode).toString("base64")}`);
const [virtualServerCode, virtualClientCode] = await Promise.all([bundle(true, "virtual"), bundle(false, "virtual")]);
const { renderControls: renderVirtual } = await import(`data:text/javascript;base64,${Buffer.from(virtualServerCode).toString("base64")}`);
const css = await readFile(new URL("../../src/ui/kobalte/kobalte.css", import.meta.url), "utf8")
  + await readFile(new URL("../../src/ui/tokens.css", import.meta.url), "utf8")
  + (await readFile(new URL("../../src/ui/foundations.css", import.meta.url), "utf8")).replace(/@import[^;]+;/g, "")
  + (await readFile(new URL("../../src/ui/composites.css", import.meta.url), "utf8")).replace(/@import[^;]+;/g, "")
  + await readFile(new URL("regression.css", import.meta.url), "utf8");
const virtualCss = await readFile(new URL("../../src/ui/virtual/virtual.css", import.meta.url), "utf8")
  + await readFile(new URL("virtual.css", import.meta.url), "utf8");

const server = createServer((request, response) => {
  if (request.url === "/virtual.css") {
    response.writeHead(200, { "content-type": "text/css" });
    response.end(virtualCss);
    return;
  }
  if (request.url === "/virtual-client.js") {
    response.writeHead(200, { "content-type": "text/javascript" });
    response.end(virtualClientCode);
    return;
  }
  if (request.url === "/controls.css") {
    response.writeHead(200, { "content-type": "text/css" });
    response.end(css);
    return;
  }
  if (request.url === "/client.js") {
    response.writeHead(200, { "content-type": "text/javascript" });
    response.end(clientCode);
    return;
  }
  const nonce = randomBytes(24).toString("base64");
  try {
    const virtual = request.url === "/virtual";
    const content = virtual ? renderVirtual(nonce) : renderControls(nonce);
    response.writeHead(200, { "content-type": "text/html", "content-security-policy": createContentSecurityPolicy(nonce) });
    response.end(`<!doctype html><html lang="en"><head><title>Control compatibility</title><link rel="stylesheet" href="/${virtual ? "virtual" : "controls"}.css"></head><body>${content}<script type="module" src="/${virtual ? "virtual-client" : "client"}.js" nonce="${nonce}"></script></body></html>`);
  } catch (error) {
    console.error(error);
    response.writeHead(500);
    response.end("Control SSR failed");
  }
});

server.listen(4174, "127.0.0.1");
for (const signal of ["SIGINT", "SIGTERM"] as const) process.on(signal, () => server.close());

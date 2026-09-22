import { randomBytes } from "node:crypto";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { build } from "vite";
import solid from "vite-plugin-solid";

import { createContentSecurityPolicy } from "../../src/security/csp.ts";

async function bundle(server: boolean) {
  const output = await build({
    configFile: false,
    root: resolve(import.meta.dirname, "../.."),
    plugins: [solid({ ssr: true })],
    logLevel: "warn",
    resolve: { alias: {
      "solid-js/web": "@solidjs/web",
      ...(process.env.KOBALTE_COMPATIBILITY === "1" ? {
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
        ? { ssr: resolve(import.meta.dirname, "controls-server.tsx") }
        : { lib: { entry: resolve(import.meta.dirname, "controls-client.tsx"), formats: ["es" as const] } }),
    },
  });
  if ("close" in output) throw new Error("Expected one-shot build");
  const code = (Array.isArray(output) ? output[0] : output).output.find((chunk) => chunk.type === "chunk");
  if (code?.type !== "chunk") throw new Error("Expected JavaScript bundle");
  return code.code;
}

const [serverCode, clientCode] = await Promise.all([bundle(true), bundle(false)]);
const { renderControls } = await import(`data:text/javascript;base64,${Buffer.from(serverCode).toString("base64")}`);
const css = await readFile(new URL("../../src/ui/kobalte/kobalte.css", import.meta.url), "utf8");

const server = createServer((request, response) => {
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
    const content = renderControls(nonce);
    response.writeHead(200, { "content-type": "text/html", "content-security-policy": createContentSecurityPolicy(nonce) });
    response.end(`<!doctype html><html lang="en"><head><title>Control compatibility</title><link rel="stylesheet" href="/controls.css"></head><body>${content}<script type="module" src="/client.js" nonce="${nonce}"></script></body></html>`);
  } catch (error) {
    console.error(error);
    response.writeHead(500);
    response.end("Control SSR failed");
  }
});

server.listen(4174, "127.0.0.1");
for (const signal of ["SIGINT", "SIGTERM"] as const) process.on(signal, () => server.close());

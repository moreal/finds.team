import { tanstackStart } from "@tanstack/solid-start/plugin/vite";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";
import solid from "vite-plugin-solid";

const solidServerFunctionsSource =
  "@solidjs/web/server-functions/server?finds-native";
const solidServerFunctionsCompatId = "\0finds:solid-server-functions-compat";

// TanStack Start rc.8 still imports the two action-URL helper names removed
// by Solid Web rc.9. Keep this adapter beside the pinned compatibility graph.
const solidServerFunctionsCompat: Plugin = {
  enforce: "pre",
  load(id) {
    if (id !== solidServerFunctionsCompatId) return;

    return `
      export * from ${JSON.stringify(solidServerFunctionsSource)};
      import {
        parseServerFunctionActionUrl,
        serverFunctionActionUrl,
      } from ${JSON.stringify(solidServerFunctionsSource)};

      export const parseServerFunctionUrl = parseServerFunctionActionUrl;
      export function serverFunctionUrl(id, boundArgs = []) {
        return serverFunctionActionUrl(id, ...boundArgs);
      }
    `;
  },
  name: "finds:solid-server-functions-compat",
  resolveId(id) {
    if (id === "@solidjs/web/server-functions/server") {
      return solidServerFunctionsCompatId;
    }
  },
};

export default defineConfig({
  plugins: [
    solidServerFunctionsCompat,
    tanstackStart(),
    solid({ ssr: true }),
  ],
  resolve: {
    alias: {
      "solid-js/web": "@solidjs/web",
    },
  },
  test: {
    environment: "node",
  },
});

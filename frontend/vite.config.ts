import { tanstackStart } from "@tanstack/solid-start/plugin/vite";
import { defineConfig } from "vitest/config";
import { cjsInterop } from "vite-plugin-cjs-interop";
import relay from "vite-plugin-relay-lite";
import solid from "vite-plugin-solid";

export default defineConfig({
  plugins: [
    tanstackStart(),
    relay({ cwd: import.meta.dirname }),
    cjsInterop({ dependencies: ["relay-runtime"] }),
    solid({ ssr: true }),
  ],
  resolve: {
    alias: {
      "solid-js/web": "@solidjs/web",
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.{ts,tsx}"],
  },
});

import { tanstackStart } from "@tanstack/solid-start/plugin/vite";
import { defineConfig } from "vitest/config";
import solid from "vite-plugin-solid";

export default defineConfig({
  plugins: [tanstackStart(), solid({ ssr: true })],
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

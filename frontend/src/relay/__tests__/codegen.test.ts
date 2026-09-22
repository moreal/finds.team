import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { expect, it } from "vitest";

import { prepareRelaySchema } from "../../../scripts/relay";

it("copies canonical schema bytes on every compiler invocation, replacing stale copies", async () => {
  const root = await mkdtemp(join(tmpdir(), "finds-relay-schema-"));
  try {
    await mkdir(join(root, "frontend"));
    await writeFile(join(root, "frontend/relay.config.json"), JSON.stringify({ schema: "../schema.graphqls" }));
    const canonical = Buffer.from("# 한글\r\ntype Query { first: String }\r\n");
    await writeFile(join(root, "schema.graphqls"), canonical);
    const copy = await prepareRelaySchema(join(root, "frontend"));
    expect(await readFile(copy)).toEqual(canonical);

    await writeFile(copy, "stale schema");
    const changed = Buffer.from("type Query { second: Int }\n");
    await writeFile(join(root, "schema.graphqls"), changed);
    expect(await prepareRelaySchema(join(root, "frontend"))).toBe(copy);
    expect(await readFile(copy)).toEqual(changed);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

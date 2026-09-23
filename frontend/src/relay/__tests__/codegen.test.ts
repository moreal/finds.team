import { spawnSync } from "node:child_process";
import { copyFile, mkdtemp, mkdir, readFile, realpath, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
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

it("the real launcher fails on missing, stale or schema-invalid artifacts without silently refreshing them", async () => {
  // Node canonicalizes import.meta.url; keep argv on the same path on macOS (/var -> /private/var).
  const root = await realpath(await mkdtemp(join(tmpdir(), "finds-relay-compiler-")));
  const frontend = resolve(import.meta.dirname, "../../..");
  try {
    await mkdir(join(root, "src/__generated__"), { recursive: true });
    await mkdir(join(root, "scripts"));
    await copyFile(join(frontend, "scripts/relay.ts"), join(root, "scripts/relay.ts"));
    await symlink(join(frontend, "node_modules"), join(root, "node_modules"));
    await writeFile(join(root, "package.json"), JSON.stringify({ type: "module" }));
    await writeFile(join(root, "relay.config.json"), JSON.stringify({
      src: "./src", schema: "./schema.graphqls", language: "typescript",
      artifactDirectory: "./src/__generated__", noSourceControl: true,
    }));
    await writeFile(join(root, "schema.graphqls"), "type Query { viewer: String }\n");
    const source = join(root, "src/Probe.ts");
    // Build fixture tags at runtime so Vite's Relay transform does not compile strings in this test.
    const operation = (selection: string) => [
      'import { graphql } from "relay-runtime"; export const query = graphql',
      `query ProbeQuery { ${selection} }`, ";",
    ].join("`");
    await writeFile(source, operation("viewer"));
    const run = (...args: string[]) => spawnSync(process.execPath, [join(root, "scripts/relay.ts"), ...args], {
      cwd: root, encoding: "utf8", timeout: 10_000,
    });
    const generated = run();
    expect(generated.status, generated.stderr).toBe(0);
    expect(run("--validate").status).toBe(0);
    const artifact = join(root, "src/__generated__/ProbeQuery.graphql.ts");
    const original = await readFile(artifact);
    await rm(artifact);
    const missing = run("--validate");
    expect(missing.status, missing.stdout + missing.stderr).toBe(1);
    await expect(readFile(artifact)).rejects.toMatchObject({ code: "ENOENT" });
    await writeFile(artifact, original);
    expect(run("--validate").status).toBe(0);
    await writeFile(source, operation("renamed: viewer"));
    const stale = run("--validate");
    expect(stale.status, stale.stdout + stale.stderr).toBe(1);
    expect(await readFile(artifact)).toEqual(original);
    expect(run().status).toBe(0);
    expect(await readFile(artifact)).not.toEqual(original);
    expect(run("--validate").status).toBe(0);
    const invalidSchema = Buffer.from("# changed canonical schema\r\ntype Query { other: String }\r\n");
    await writeFile(join(root, "schema.graphqls"), invalidSchema);
    const invalid = run("--validate");
    expect(invalid.status, invalid.stdout + invalid.stderr).toBe(1);
    expect(await readFile(join(root, ".relay/schema.graphql"))).toEqual(invalidSchema);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}, 30_000);

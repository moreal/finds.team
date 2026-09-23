import { spawnSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { expect, test } from "vitest";

const root = resolve(import.meta.dirname, "../../..");
const stages = [
  "pnpm --dir frontend relay:validate",
  "pnpm --dir frontend test --run src/relay/__tests__/codegen.test.ts",
  "gradle :domain:test :application:test :adapter-persistence:test :adapter-graphql:test :bootstrap:test",
];

async function runGate(failAt = "") {
  const fixture = await mkdtemp(join(tmpdir(), "finds-relay-check-"));
  try {
    await mkdir(join(fixture, "backend"));
    const log = join(fixture, "calls.log");
    for (const [path, name] of [["pnpm", "pnpm"], ["backend/gradlew", "gradle"]]) {
      await writeFile(join(fixture, path), `#!/bin/sh
printf '%s\\n' '${name} '"$*" >> "$RELAY_CHECK_LOG"
if [ '${name} '"$*" = "$RELAY_CHECK_FAIL" ]; then exit 23; fi
`);
      await chmod(join(fixture, path), 0o755);
    }
    const manifest = JSON.parse(await readFile(join(root, "package.json"), "utf8"));
    expect(manifest.scripts["relay:check"]).toEqual(expect.any(String));
    const result = spawnSync(manifest.scripts["relay:check"], {
      cwd: fixture,
      shell: true,
      encoding: "utf8",
      env: { ...process.env, PATH: `${fixture}:${process.env.PATH ?? ""}`, RELAY_CHECK_LOG: log, RELAY_CHECK_FAIL: failAt },
    });
    return { result, calls: (await readFile(log, "utf8")).trim().split("\n") };
  } finally {
    await rm(fixture, { recursive: true, force: true });
  }
}

test("relay:check validates committed artifacts before tests can regenerate them and runs the complete backend contract", async () => {
  const { result, calls } = await runGate();
  expect(result.status, result.stderr).toBe(0);
  expect(calls).toEqual(stages);
});

test.each(stages)("relay:check fails closed when %s fails", async (stage) => {
  const { result, calls } = await runGate(stage);
  expect(result.status).toBe(23);
  expect(calls).toEqual(stages.slice(0, stages.indexOf(stage) + 1));
});

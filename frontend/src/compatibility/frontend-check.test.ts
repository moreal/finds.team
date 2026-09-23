import { spawnSync } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { expect, test } from "vitest";

const repositoryRoot = resolve(import.meta.dirname, "../../..");
const expectedSteps = [
  "--dir frontend relay:validate",
  "--dir frontend typecheck",
  "--dir frontend test --run",
  "--dir frontend test:e2e",
  "--dir frontend build",
  "--dir frontend test:built",
];

async function runGate(failAt = "") {
  const fixture = await mkdtemp(join(tmpdir(), "finds-team-frontend-check-"));
  const binary = join(fixture, "pnpm");
  const log = join(fixture, "calls.log");
  await writeFile(binary, `#!/bin/sh
printf '%s\\n' "$*" >> "$FRONTEND_CHECK_LOG"
if [ "$*" = "$FRONTEND_CHECK_FAIL" ]; then
  exit 23
fi
`);
  await chmod(binary, 0o755);

  try {
    const manifest = JSON.parse(await readFile(join(repositoryRoot, "package.json"), "utf8"));
    expect(manifest.scripts?.["frontend:check"]).toEqual(expect.any(String));
    const result = spawnSync(manifest.scripts["frontend:check"], {
      cwd: repositoryRoot,
      encoding: "utf8",
      env: {
        ...process.env,
        FRONTEND_CHECK_FAIL: failAt,
        FRONTEND_CHECK_LOG: log,
        PATH: `${fixture}:${process.env.PATH ?? ""}`,
      },
      shell: true,
    });
    const calls = (await readFile(log, "utf8")).trim().split("\n").filter(Boolean);
    return { calls, result };
  } finally {
    await rm(fixture, { recursive: true, force: true });
  }
}

test("frontend:check runs the compatibility stages in the required order", async () => {
  const { calls, result } = await runGate();

  expect(result.status, result.stderr).toBe(0);
  expect(calls).toEqual(expectedSteps);
});

test("frontend:check stops at the first failed compatibility stage", async () => {
  const { calls, result } = await runGate(expectedSteps[2]);

  expect(result.status).toBe(23);
  expect(calls).toEqual(expectedSteps.slice(0, 3));
});

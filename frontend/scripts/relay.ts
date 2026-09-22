import { spawn } from "node:child_process";
import { copyFile, mkdir, readFile, watch, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

// Relay 20 accepts .graphql/.gql only. Keep Spring's canonical .graphqls file.
export async function prepareRelaySchema(root: string): Promise<string> {
  const config = JSON.parse(await readFile(join(root, "relay.config.json"), "utf8"));
  const directory = join(root, ".relay");
  await mkdir(directory, { recursive: true });
  const target = join(directory, "schema.graphql");
  await copyFile(resolve(root, config.schema), target);
  // Relay 20 no longer supports CLI schema overrides, so derive its config too.
  await writeFile(join(directory, "relay.config.json"), JSON.stringify({
    ...config,
    src: resolve(root, config.src ?? "src"),
    artifactDirectory: resolve(root, config.artifactDirectory ?? "src/__generated__"),
    schema: target,
  }));
  return target;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await prepareRelaySchema(projectRoot);
  const require = createRequire(import.meta.url);
  // vite-plugin-relay-lite also passes the original config path positionally.
  const args = process.argv.slice(2).filter((arg) => resolve(arg) !== join(projectRoot, "relay.config.json"));
  const child = spawn(process.execPath, [require.resolve("relay-compiler/cli.js"), ...args, join(projectRoot, ".relay/relay.config.json")], {
    cwd: projectRoot,
    stdio: "inherit",
  });
  const controller = new AbortController();
  child.on("exit", (code) => {
    controller.abort();
    process.exitCode = code ?? 1;
  });
  child.on("error", (error) => {
    controller.abort();
    console.error(error);
    process.exitCode = 1;
  });
  if (args.includes("--watch") || args.includes("-w")) {
    const config = JSON.parse(await readFile(join(projectRoot, "relay.config.json"), "utf8"));
    try {
      for await (const _ of watch(dirname(resolve(projectRoot, config.schema)), { signal: controller.signal })) {
        await prepareRelaySchema(projectRoot);
      }
    } catch (error) {
      if (!controller.signal.aborted) throw error;
    }
  }
}

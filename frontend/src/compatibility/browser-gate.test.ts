import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, expect, test, vi } from 'vitest';
import { verifyCatalogVisual } from '../../e2e/fixtures/catalog-visual-policy';

afterEach(() => { vi.unstubAllEnvs(); vi.resetModules(); });

for (const runtime of [
  { platform: 'linux', remoteBrowser: false },
  { platform: 'win32', remoteBrowser: false },
  { platform: 'darwin', remoteBrowser: true },
] as const) {
  test(`${runtime.platform} remote=${runtime.remoteBrowser} captures an artifact without requiring an absent golden`, async () => {
    const directory = await mkdtemp(join(tmpdir(), 'catalog-policy-'));
    const artifact = join(directory, 'capture.png');
    try {
      await verifyCatalogVisual(runtime, {
        compareGolden: async () => { await readFile(join(directory, 'absent-platform-golden.png')); },
        captureArtifact: async () => { await writeFile(artifact, 'captured browser bytes'); },
      });
      expect(await readFile(artifact, 'utf8')).toBe('captured browser bytes');
    } finally { await rm(directory, { recursive: true, force: true }); }
  });
}

test('native Darwin still propagates a golden comparison failure', async () => {
  await expect(verifyCatalogVisual({ platform: 'darwin', remoteBrowser: false }, {
    compareGolden: async () => { throw new Error('pixels differ'); },
    captureArtifact: async () => {},
  })).rejects.toThrow('pixels differ');
});

test('an inherited production origin does not disable or select fixture servers', async () => {
  vi.stubEnv('FINDS_PUBLIC_ORIGIN', 'https://production.example.test');
  const { default: config } = await import('../../playwright.config');
  expect(config.webServer).toHaveLength(3);
  expect(config.projects?.some(project => project.name === 'production')).toBe(false);
});

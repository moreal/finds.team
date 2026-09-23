export async function verifyCatalogVisual(
  runtime: { platform: NodeJS.Platform; remoteBrowser: boolean },
  actions: { compareGolden: () => Promise<void>; captureArtifact: () => Promise<void> },
) {
  if (runtime.platform === 'darwin' && !runtime.remoteBrowser) await actions.compareGolden();
  else await actions.captureArtifact();
}

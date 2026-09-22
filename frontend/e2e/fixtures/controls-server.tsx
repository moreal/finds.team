import { generateHydrationScript, renderToString } from "@solidjs/web";

import { Controls } from "./Controls";

export function renderControls(nonce: string) {
  return `${generateHydrationScript({ nonce })}<div id="root">${renderToString(() => <Controls />, { manifest: {}, nonce })}</div>`;
}

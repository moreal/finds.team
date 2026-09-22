import { generateHydrationScript, renderToString } from "@solidjs/web";
import { VirtualLists } from "./VirtualLists";

export function renderControls(nonce: string) {
  return `${generateHydrationScript({ nonce })}<div id="root">${renderToString(() => <VirtualLists />, { manifest: {}, nonce })}</div>`;
}

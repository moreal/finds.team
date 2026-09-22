import { hydrate } from "@solidjs/web";

import { Controls } from "./Controls";

await hydrate(() => <Controls />, document.getElementById("root")!);
document.documentElement.dataset.hydrated = "true";

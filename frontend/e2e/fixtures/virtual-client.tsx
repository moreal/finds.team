import { hydrate } from "@solidjs/web";
import { VirtualLists } from "./VirtualLists";

await hydrate(() => <VirtualLists />, document.getElementById("root")!);
document.documentElement.dataset.hydrated = "true";

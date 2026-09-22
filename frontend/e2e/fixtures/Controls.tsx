import { createSignal } from "solid-js";

import { Dialog } from "../../src/ui/kobalte/Dialog";
import { Select } from "../../src/ui/kobalte/Select";

export function Controls() {
  const options = [{ id: "all", label: "All roles" }, { id: "engineering", label: "Engineering" }];
  const [selected, setSelected] = createSignal(options[0]);
  const [open, setOpen] = createSignal(false);
  return (
    <main>
      <button type="button">Before controls</button>
      <Dialog trigger="Edit preferences" title="Preferences" description="Choose your preferences." closeLabel="Close preferences">
        <button type="button">First action</button>
        <button type="button">Last action</button>
      </Dialog>
      <form>
        <Select label="Role" name="role" options={options} value={selected()}
          getOptionValue={(option) => option.id} getOptionLabel={(option) => option.label}
          onChange={setSelected} />
        <output aria-label="Selected role">{selected().label}</output>
      </form>
      <Dialog trigger="Controlled dialog" title="Controlled preferences" closeLabel="Dismiss controlled dialog"
        open={open()} onOpenChange={setOpen}>
        <form method="dialog"><button type="submit">Finish</button></form>
      </Dialog>
      <button type="button" onClick={() => setOpen(true)}>Open externally</button>
      <output aria-label="Controlled dialog state">{open() ? "open" : "closed"}</output>
      <button type="button">After controls</button>
    </main>
  );
}

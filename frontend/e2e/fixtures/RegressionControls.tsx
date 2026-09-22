import { createSignal } from "solid-js";

import { Dialog } from "../../src/ui/kobalte/Dialog";
import { Select } from "../../src/ui/kobalte/Select";

export function RegressionControls() {
  const [open, setOpen] = createSignal(false);
  const options = [{ id: "all", label: "All roles" }, { id: "engineering", label: "Engineering" }];
  const [selected, setSelected] = createSignal(options[0]);
  let first!: HTMLButtonElement;

  return (
    <>
      <Dialog trigger="Keyboard cases" title="Keyboard cases" closeLabel="Close keyboard cases">
        <p>Every visible field must remain reachable in a small dialog, including native editable text.</p>
        <button ref={first} type="button">Before hidden</button>
        <span class="regression-hidden"><button type="button">Hidden action</button></span>
        <button type="button">After hidden</button>
        <div contenteditable="true" role="textbox" aria-label="Native editable">Editable text</div>
        <button type="button">After editable</button>
        <button type="button" onFocus={() => first.focus()}>Redirected focus</button>
        <button type="button">After redirect</button>
        <button type="button" onKeyDown={(event) => {
          if (event.key === "Tab") event.preventDefault();
        }}>Handles Tab</button>
      </Dialog>
      <Dialog trigger="Held dialog" title="Held preferences" closeLabel="Dismiss held dialog"
        open={open()} onOpenChange={(next) => { if (next) setOpen(true); }}>
        <form method="dialog"><button type="submit">Finish held dialog</button></form>
        <button type="button" onClick={() => setOpen(false)}>Accept external close</button>
      </Dialog>
      <output aria-label="Held dialog state">{open() ? "open" : "closed"}</output>
      <form>
        <Select label="Held role" name="held-role" options={options} value={selected()}
          getOptionValue={(option) => option.id} getOptionLabel={(option) => option.label}
          onChange={() => undefined} />
        <output aria-label="Held role state">{selected().label}</output>
      </form>
      <button type="button" onClick={() => setSelected(options[1])}>Select engineering externally</button>
      <button type="button" onClick={() => setSelected(options[0])}>Select all externally</button>
    </>
  );
}

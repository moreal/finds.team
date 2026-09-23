import { createSignal } from "solid-js";
import { Combobox } from "../../src/ui/Combobox";
import { Popover } from "../../src/ui/Popover";
import { Tooltip } from "../../src/ui/Tooltip";
import { Tabs } from "../../src/ui/Tabs";
import { Toast } from "../../src/ui/Toast";
import { Button } from "../../src/ui/Button";

export function CompositeControls() {
  const [skill, setSkill] = createSignal("");
  const [tab, setTab] = createSignal("jobs");
  const [message, setMessage] = createSignal("");
  return <section aria-label="Composite controls">
    <Combobox label="Skill search" options={[{ value: "solid", label: "Solid" }, { value: "rust", label: "Rust" }]} value={skill()} onChange={setSkill} />
    <output aria-label="Selected skill">{skill()}</output>
    <Popover trigger="More filters" title="Filter options"><Button>Apply filter</Button></Popover>
    <Tabs label="Results" value={tab()} onChange={setTab} tabs={[{ value: "jobs", label: "Jobs", content: "Job results" }, { value: "companies", label: "Companies", content: "Company results" }]} />
    <Tooltip label="Search help" content="Search by skill" />
    <Button onClick={() => setMessage("Saved changes")}>Save changes</Button>
    <Toast message={message()} onDismiss={() => setMessage("")} />
  </section>;
}

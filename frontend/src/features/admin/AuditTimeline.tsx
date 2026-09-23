import { For } from "solid-js";
import "../../ui/composites.css";

export interface AuditEvent { id: string; actor: string; action: string; target: string; occurredAt: string; timeLabel: string }
export interface AuditTimelineProps { items: readonly AuditEvent[] }

export function AuditTimeline(props: AuditTimelineProps) {
  return props.items.length ? <ol class="ui-record-list" aria-label="감사 기록">
    <For each={props.items}>{item => <li class="ui-record-row">
      <div><strong>{item.action}</strong><p>{item.actor} · {item.target}</p></div>
      <time class="ui-muted ui-tabular" datetime={item.occurredAt}>{item.timeLabel}</time>
    </li>}</For>
  </ol> : <p role="status">감사 기록이 없어요.</p>;
}

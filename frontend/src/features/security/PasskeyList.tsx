import { For } from "solid-js";
import { Button } from "../../ui/Button";
import "../../ui/composites.css";

export interface PasskeyItem { id: string; name: string; createdAt: string; createdLabel: string }
export interface PasskeyListProps {
  items: readonly PasskeyItem[]; pendingId?: string; error?: string;
  /** Opens the caller's confirmation flow; must not delete immediately. */
  onRemove?: (id: string) => void;
}

export function PasskeyList(props: PasskeyListProps) {
  return <div>
    <div role="status" aria-live="polite" aria-atomic="true">{props.pendingId ? "Passkey를 삭제하는 중이에요." : props.error ?? (props.items.length ? "" : "등록된 Passkey가 없어요.")}</div>
    {props.items.length > 0 && <ul class="ui-record-list" aria-label="Passkey 목록">
      <For each={props.items}>{item => <li class="ui-record-row">
        <div><strong>{item.name}</strong><p class="ui-muted ui-tabular">등록일 <time datetime={item.createdAt}>{item.createdLabel}</time></p></div>
        {props.onRemove && <Button variant="danger" disabled={!!props.pendingId} loading={props.pendingId === item.id}
          aria-label={`${item.name} Passkey 삭제`} onClick={() => props.onRemove?.(item.id)}>삭제</Button>}
      </li>}</For>
    </ul>}
  </div>;
}

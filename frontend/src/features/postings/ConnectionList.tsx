import type { JSX } from "@solidjs/web";
import { For } from "solid-js";
import { AsyncState, type AsyncStateProps } from "../../ui/AsyncState";
import { Button } from "../../ui/Button";
import "../../ui/composites.css";

export interface ConnectionListProps<T> extends Omit<AsyncStateProps, "children"> {
  items: readonly T[];
  renderItem: (item: T) => JSX.Element;
  hasNextPage?: boolean;
  onLoadMore?: () => void;
  label?: string;
  /** Optional measured, progressively enhanced list renderer. */
  renderItems?: () => JSX.Element;
}

/** Server cursors remain authoritative. Pagination preserves already loaded rows. */
export function ConnectionList<T>(props: ConnectionListProps<T>) {
  return <AsyncState state={props.state} constraints={props.constraints} correlationId={props.correlationId}
    onRetry={props.onRetry} onClearFilters={props.onClearFilters} skeleton={props.skeleton}>
    {props.renderItems ? props.renderItems() : <ul class="ui-record-list" aria-label={props.label ?? "공고 목록"}>
      <For each={props.items}>{item => <li>{props.renderItem(item)}</li>}</For>
    </ul>}
    {props.hasNextPage && props.onLoadMore && <Button variant="secondary" loading={props.state === "pagination-pending"}
      onClick={props.onLoadMore}>{props.state === "pagination-pending" ? "불러오는 중…" : "더 보기"}</Button>}
  </AsyncState>;
}

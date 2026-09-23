import type { JSX } from "@solidjs/web";
import { Button } from "./Button";
import { Skeleton } from "./Skeleton";

export type AsyncStateKind = "pending" | "pagination-pending" | "empty" | "filtered-empty" | "error" | "unauthorized" | "forbidden" | "data";
export interface AsyncStateProps {
  state: AsyncStateKind;
  children?: JSX.Element;
  skeleton?: JSX.Element;
  constraints?: string;
  correlationId?: string;
  onRetry?: () => void;
  /** Clears the constraints named above, allowing callers to remove a targeted subset. */
  onClearFilters?: () => void;
}

const messages = {
  pending: "공고를 불러오는 중이에요.",
  "pagination-pending": "공고를 더 불러오는 중이에요.",
  empty: "아직 공고가 없어요.",
  "filtered-empty": "선택한 조건에 맞는 공고가 없어요.",
  error: "공고를 불러오지 못했어요.",
  unauthorized: "로그인이 필요해요.",
  forbidden: "접근 권한이 없어요.",
  data: "",
};

export function AsyncState(props: AsyncStateProps) {
  return <div class="ui-async-state">
    {(props.state === "data" || props.state === "pagination-pending") && props.children}
    {props.state !== "data" && <div class="ui-async-message" role="status" aria-live="polite" aria-atomic="true">
      <p>{messages[props.state]}</p>
      {props.state === "filtered-empty" && <p class="ui-muted">{props.constraints}</p>}
      {props.state === "error" && props.correlationId && <p class="ui-muted">문의 번호: <code>{props.correlationId}</code></p>}
    </div>}
    {props.state === "pending" && (props.skeleton ?? <Skeleton shape="card" />)}
    {props.state === "error" && props.onRetry && <Button variant="secondary" onClick={props.onRetry}>다시 시도</Button>}
    {props.state === "filtered-empty" && props.onClearFilters && <Button variant="secondary" onClick={props.onClearFilters}>조건 해제</Button>}
  </div>;
}

import type { JSX } from "@solidjs/web";
import { createMemo, createSignal, For, onCleanup } from "solid-js";
import { Button } from "../../ui/Button";
import { jobsFailure, type JobsFailure } from "../postings/JobsPageQuery";
import { AsyncState } from "../../ui/AsyncState";

export type DetailConnectionData<Node> = {
  readonly edges: ReadonlyArray<{ readonly node: Node }>;
  readonly totalCount: number;
  readonly pageInfo: { readonly hasNextPage: boolean; readonly endCursor?: string | null };
  readonly error?: { readonly code: string } | null;
};

export function DetailConnection<Node>(props: {
  title: string; moreLabel: string; connection: DetailConnectionData<Node> | undefined;
  onMore: (cursor?: string | null) => Promise<void>; children: (node: Node) => JSX.Element;
}) {
  const [pending, setPending] = createSignal(false);
  const [failure, setFailure] = createSignal<JobsFailure>();
  // Memoize by code, not by the response object: pending changes and a repeated
  // failed response must not mint new diagnostic IDs for the same visible error.
  const code = createMemo(() => props.connection?.error?.code);
  const connectionFailure = createMemo<JobsFailure | undefined>(() => {
    const value = code();
    if (!value) return undefined;
    if (value === "FORBIDDEN") return { kind: "forbidden" };
    if (["INVALID_CURSOR", "INVALID_PAGE", "INVALID_INPUT", "INVALID_FILTER", "UNKNOWN_SKILL"].includes(value)) return { kind: "validation" };
    return jobsFailure(value);
  });
  const activeFailure = () => failure() ?? connectionFailure();
  let disposed = false;
  onCleanup(() => { disposed = true; });
  async function load(restart = false) {
    if (pending()) return;
    setPending(true); setFailure(undefined);
    try { await props.onMore(restart ? undefined : props.connection?.pageInfo.endCursor); }
    catch (error) { if (!disposed) setFailure(jobsFailure(error)); }
    finally { if (!disposed) setPending(false); }
  }
  return <section aria-label={props.title} aria-busy={pending() ? "true" : "false"}>
    <h2>{props.title}</h2><p class="detail-count" role="status" aria-live="polite">{props.connection?.totalCount ?? 0}개{pending() ? " · 불러오는 중…" : ""}</p>
    <div class="detail-postings"><For each={props.connection?.edges ?? []}>{edge => props.children(edge.node)}</For></div>
    {!props.connection?.edges.length && !failure() && !props.connection?.error && <p>아직 표시할 항목이 없어요.</p>}
    {activeFailure()?.kind === "validation"
      ? <div role="status"><p>목록을 새로 불러와 주세요.</p><Button variant="secondary" disabled={pending()} onClick={() => void load(true)}>처음부터 다시 불러오기</Button></div>
      : activeFailure() && <AsyncState state={activeFailure()!.kind} correlationId={activeFailure()?.correlationId} onRetry={() => void load()} />}
    {props.connection?.pageInfo.hasNextPage && !failure() && !props.connection?.error && <Button variant="secondary" disabled={pending()} onClick={() => void load()}>{pending() ? "불러오는 중…" : props.moreLabel}</Button>}
  </section>;
}

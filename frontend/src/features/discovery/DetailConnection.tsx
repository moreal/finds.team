import type { JSX } from "@solidjs/web";
import { createSignal, For, onCleanup } from "solid-js";
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
  let disposed = false;
  onCleanup(() => { disposed = true; });
  async function load() {
    if (pending()) return;
    setPending(true); setFailure(undefined);
    try { await props.onMore(props.connection?.pageInfo.endCursor); }
    catch (error) { if (!disposed) setFailure(jobsFailure(error)); }
    finally { if (!disposed) setPending(false); }
  }
  return <section aria-label={props.title} aria-busy={pending() ? "true" : "false"}>
    <h2>{props.title}</h2><p class="detail-count" role="status" aria-live="polite">{props.connection?.totalCount ?? 0}개{pending() ? " · 불러오는 중…" : ""}</p>
    <div class="detail-postings"><For each={props.connection?.edges ?? []}>{edge => props.children(edge.node)}</For></div>
    {!props.connection?.edges.length && !failure() && !props.connection?.error && <p>아직 표시할 항목이 없어요.</p>}
    {(failure() || props.connection?.error) && <AsyncState state={failure()?.kind ?? "error"} correlationId={failure()?.correlationId} onRetry={() => void load()} />}
    {props.connection?.pageInfo.hasNextPage && !failure() && !props.connection?.error && <Button variant="secondary" disabled={pending()} onClick={() => void load()}>{pending() ? "불러오는 중…" : props.moreLabel}</Button>}
  </section>;
}

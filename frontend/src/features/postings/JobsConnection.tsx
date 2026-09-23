import { createEffect, createMemo, createSignal, onCleanup, onSettled } from "solid-js";
import { useRelayEnvironment } from "../../relay/RelayRoot";
import { Button } from "../../ui/Button";
import { VirtualList } from "../../ui/virtual/VirtualList";
import type { DiscoveryOperations_job$data } from "../../__generated__/DiscoveryOperations_job.graphql";
import { ConnectionList } from "./ConnectionList";
import { PostingCard } from "./PostingCard";
import { fetchJobs, jobsFailure, jobsConnectionFailure, jobsOperation, readJobs, type JobsVariables, type JobsFailure } from "./JobsPageQuery";
import { AsyncState } from "../../ui/AsyncState";

function JobCard(props: { item: DiscoveryOperations_job$data }) {
  const employment: Record<string, string> = { FULL_TIME: "정규직", PART_TIME: "시간제", CONTRACT: "계약직", INTERNSHIP: "인턴", UNKNOWN: "고용 형태 미분류" };
  const remote: Record<string, string> = { REMOTE: "원격", HYBRID: "하이브리드", ONSITE: "출근", UNKNOWN: "근무 방식 미분류" };
  return <PostingCard title={props.item.title} href={`/jobs/${encodeURIComponent(props.item.id)}`}
    company={props.item.careerSite.displayName} companyHref={`/companies/${encodeURIComponent(props.item.careerSite.slug)}`}
    metadata={[props.item.classification?.location?.displayName, employment[props.item.classification?.employment?.value ?? ""], remote[props.item.classification?.remote?.value ?? ""]].filter((value): value is string => !!value)}
    skills={props.item.classification?.skills.map(item => item.skill?.displayName ?? item.text) ?? []} />;
}

export function JobsConnection(props: { variables: JobsVariables; failure?: JobsFailure; constraints: string; onClear: () => void }) {
  const environment = useRelayEnvironment();
  const [revision, setRevision] = createSignal(0);
  const [pending, setPending] = createSignal(false);
  const [failure, setFailure] = createSignal(props.failure);
  const [enhanced, setEnhanced] = createSignal(false);
  let list!: HTMLDivElement;
  let disposed = false;
  const data = createMemo(() => { revision(); return readJobs(environment(), props.variables); });
  onCleanup(() => { disposed = true; });
  createEffect(() => props.variables, variables => {
    setFailure(props.failure);
    setPending(false);
    setEnhanced(false);
    const operation = jobsOperation(variables);
    const retained = environment().retain(operation);
    const subscription = environment().subscribe(environment().lookup(operation.fragment), () => setRevision(value => value + 1));
    return () => { retained.dispose(); subscription.dispose(); };
  });
  onSettled(() => {
    const observer = new ResizeObserver(() => {
      if (data().items.length > 40 && list.scrollHeight > window.innerHeight * 3 && !list.contains(document.activeElement)) setEnhanced(true);
    });
    observer.observe(list);
    return () => observer.disconnect();
  });
  async function loadMore(retry = false) {
    if (pending()) return;
    const variables = props.variables;
    setPending(true);
    setFailure(undefined);
    try {
      await fetchJobs(environment(), { ...variables, ...(retry ? {} : { after: data().connection?.pageInfo.endCursor }) }, true);
      if (!disposed && variables === props.variables) {
        const code = readJobs(environment(), variables).connection?.error?.code;
        if (code) setFailure(jobsConnectionFailure(code));
      }
    } catch (error) { if (!disposed && variables === props.variables) setFailure(jobsFailure(error)); }
    finally { if (!disposed && variables === props.variables) { setPending(false); setRevision(value => value + 1); } }
  }
  const hasError = () => !!failure() || !!data().connection?.error;
  const state = () => data().items.length ? pending() ? "pagination-pending" : "data"
    : pending() ? "pending" : hasError() ? failure()?.kind ?? (data().connection?.error?.code === "FORBIDDEN" ? "forbidden" : "error") : props.constraints ? "filtered-empty" : "empty";
  return <section aria-label="검색 결과">
    <p class="jobs-count" role="status" aria-live="polite">{hasError() && !data().items.length ? "결과를 확인할 수 없어요." : `${data().connection?.totalCount ?? 0}개의 공고`}</p>
    <div ref={list}>
      <ConnectionList state={state()} items={data().items} renderItem={item => <JobCard item={item} />}
        constraints={props.constraints} correlationId={failure()?.correlationId} onClearFilters={props.onClear} onRetry={() => void loadMore(true)}
        hasNextPage={data().connection?.pageInfo.hasNextPage} onLoadMore={() => void loadMore()}
        renderItems={enhanced() ? () => <VirtualList items={data().items} enabled estimateSize={() => 220} getKey={item => item.id}>
          {item => <JobCard item={item()} />}
        </VirtualList> : undefined} />
    </div>
    {hasError() && data().items.length > 0 && (failure()?.kind === "validation"
      ? <AsyncState state="validation" onClearFilters={props.onClear} />
      : <div role="status"><p>다음 공고를 불러오지 못했어요. 기존 결과는 유지돼요.</p>
      {failure()?.correlationId && <p>문의 번호: <code>{failure()!.correlationId}</code></p>}
      <Button variant="secondary" onClick={() => void loadMore()}>다시 시도</Button></div>)}
  </section>;
}

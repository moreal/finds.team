import { useRouter } from "@tanstack/solid-router";
import { AsyncState } from "../../ui/AsyncState";
import { Link } from "../../ui/Link";
import type { Environment, GraphQLTaggedNode, Variables } from "relay-runtime";
import { fetchDetail } from "../../relay/fragments";
import { jobsFailure, type JobsFailure } from "../postings/JobsPageQuery";
import "./details.css";

export function DetailNotFound() {
  return <main class="detail-page"><h1>찾을 수 없어요.</h1><p>주소를 확인하거나 다른 채용 공고를 둘러보세요.</p><Link href="/jobs">채용 공고 둘러보기</Link></main>;
}
export function DetailPending() {
  return <main class="detail-page"><h1>채용 정보</h1><AsyncState state="pending" /></main>;
}
export function DetailError() {
  const router = useRouter();
  return <main class="detail-page"><h1>채용 정보</h1><AsyncState state="error" onRetry={() => void router.invalidate()} /></main>;
}
export function DetailFailure(props: { failure: JobsFailure }) {
  const router = useRouter();
  return <main class="detail-page"><Link href="/jobs">← 채용 공고</Link><h1>채용 정보</h1>
    <AsyncState state={props.failure.kind} correlationId={props.failure.correlationId} onRetry={() => void router.invalidate()} />
  </main>;
}
export async function loadDetail(environment: Environment, query: GraphQLTaggedNode, variables: Variables) {
  try { await fetchDetail(environment, query, variables); return undefined; }
  catch (error) { return jobsFailure(error); }
}
export function detailHead(title: string, description: string, path: string) {
  return { meta: [{ title: `${title} | finds.team` }, { name: "description", content: description }], links: [{ rel: "canonical", href: `https://finds.team${path}` }] };
}

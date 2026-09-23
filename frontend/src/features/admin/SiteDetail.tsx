import { For } from 'solid-js';
import type { AdminOperationsHistoryQuery } from '../../__generated__/AdminOperationsHistoryQuery.graphql';
import type { AdminOperations_crawl$key } from '../../__generated__/AdminOperations_crawl.graphql';
import { crawl } from '../../relay/AdminOperations';
import { readFragment } from '../../relay/fragments';
import { useRelayEnvironment } from '../../relay/RelayRoot';
import { Link } from '../../ui/Link';
export function SiteDetail(props: { site: NonNullable<AdminOperationsHistoryQuery['response']['careerSite']> }) {
  const environment = useRelayEnvironment();
  return <>
    <section><h2>{props.site.displayName}</h2><p>{props.site.crawlSummary?.outcome === 'FAILED' ? '최근 수집 실패' : props.site.crawlSummary?.outcome === 'SUCCESS' ? '최근 수집 성공' : '완료된 수집 없음'}</p>
      <dl><dt>공급자</dt><dd>{props.site.provider}</dd><dt>채용 페이지</dt><dd>{props.site.canonicalBaseUrl}</dd><dt>최근 완료</dt><dd>{props.site.crawlSummary?.finishedAt ?? '기록 없음'}</dd></dl>
      <Link href="/admin/audit?targetType=career_site">전체 사이트 변경 감사 기록</Link>
    </section>
    <section><h2>수집 기록</h2>{!props.site.crawlHistory.edges.length && <p>수집 기록이 없어요.</p>}
      <ol class="admin-records"><For each={props.site.crawlHistory.edges}>{edge => {
        const run = () => readFragment<AdminOperations_crawl$key>(environment(), crawl, edge.node);
        return <li><h3>{run().outcome === 'FAILED' ? '실패' : run().outcome === 'SUCCESS' ? '성공' : '실행 중'}</h3>
          <p><time datetime={run().startedAt}>{run().startedAt}</time> → {run().finishedAt ?? '진행 중'}</p>
          {run().error && <p role="note">{run().error!.code}</p>}
          <p>가져옴 {run().counts.fetched} · 신규 {run().counts.inserted} · 수정 {run().counts.updated} · 확인 {run().counts.touched} · 누락 {run().counts.missing} · 마감 {run().counts.closed} · 재개 {run().counts.reopened}</p>
        </li>;
      }}</For></ol>
    </section>
  </>;
}

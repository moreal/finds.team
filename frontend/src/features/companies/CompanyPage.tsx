import type { DiscoveryOperations_company$key } from "../../__generated__/DiscoveryOperations_company.graphql";
import { companyPage, companyQuery } from "../../relay/DiscoveryOperations";
import { fetchDetail, useFragment } from "../../relay/fragments";
import { useRelayEnvironment } from "../../relay/RelayRoot";
import { Link } from "../../ui/Link";
import { DetailConnection } from "../discovery/DetailConnection";
import { FragmentPostingCard } from "../postings/FragmentPostingCard";
import "../discovery/details.css";

export function CompanyPage(props: { company: DiscoveryOperations_company$key }) {
  const company = useFragment(companyPage, () => props.company);
  const environment = useRelayEnvironment();
  return <main class="detail-page"><Link href="/jobs">← 채용 공고</Link>
    <header><p>회사</p><h1>{company().displayName}</h1>
      <Link href={company().canonicalBaseUrl} target="_blank">회사 채용 사이트 · 외부 · 새 창 ↗</Link>
      {company().crawlSummary?.finishedAt && <p class="detail-count">최근 확인 <time datetime={company().crawlSummary!.finishedAt!}>{company().crawlSummary!.finishedAt!.slice(0, 10)}</time></p>}
    </header>
    <DetailConnection title="채용 중인 공고" moreLabel="공고 더 보기" connection={company().openPostings}
      onMore={after => fetchDetail(environment(), companyQuery, { slug: company().slug, first: 20, after, orderBy: "UPDATED_DESC" }, true)}>
      {job => <FragmentPostingCard job={job} />}
    </DetailConnection>
  </main>;
}

import type { DiscoveryOperations_skill$key } from "../../__generated__/DiscoveryOperations_skill.graphql";
import { skillPage, skillQuery } from "../../relay/DiscoveryOperations";
import { fetchDetail, useFragment } from "../../relay/fragments";
import { useRelayEnvironment } from "../../relay/RelayRoot";
import { Link } from "../../ui/Link";
import { DetailConnection } from "../discovery/DetailConnection";
import { FragmentPostingCard } from "../postings/FragmentPostingCard";
import "../discovery/details.css";

export function SkillPage(props: { skill: DiscoveryOperations_skill$key }) {
  const skill = useFragment(skillPage, () => props.skill);
  const environment = useRelayEnvironment();
  function more(kind: "companies" | "postings" | "related", cursor?: string | null) {
    return fetchDetail(environment(), skillQuery, {
      slug: skill().slug, first: 20, companiesFirst: 20, relatedFirst: 20,
      includeCompanies: kind === "companies", includePostings: kind === "postings", includeRelated: kind === "related",
      ...(kind === "companies" ? { companiesAfter: cursor } : kind === "postings" ? { after: cursor } : { relatedAfter: cursor }),
    }, true);
  }
  return <main class="detail-page"><Link href="/jobs">← 채용 공고</Link>
    <header><p>기술로 찾는 기회</p><h1>#{skill().displayName}</h1>
      <dl class="detail-metadata"><div><dt>필수 기술 공고</dt><dd>{skill().requirementCounts.required}개</dd></div>
        <div><dt>우대 기술 공고</dt><dd>{skill().requirementCounts.preferred}개</dd></div><div><dt>언급된 공고</dt><dd>{skill().requirementCounts.mentioned}개</dd></div></dl>
    </header>
    <DetailConnection title="관련 회사" moreLabel="회사 더 보기" connection={skill().companies} onMore={cursor => more("companies", cursor)}>
      {company => <Link href={`/companies/${encodeURIComponent(company.slug)}`}>{company.displayName}</Link>}
    </DetailConnection>
    <DetailConnection title="채용 중인 공고" moreLabel="공고 더 보기" connection={skill().openPostings} onMore={cursor => more("postings", cursor)}>
      {job => <FragmentPostingCard job={job} />}
    </DetailConnection>
    <DetailConnection title="관련 기술" moreLabel="기술 더 보기" connection={skill().relatedSkills} onMore={cursor => more("related", cursor)}>
      {related => <Link href={`/skills/${encodeURIComponent(related.slug)}`}>#{related.displayName}</Link>}
    </DetailConnection>
  </main>;
}

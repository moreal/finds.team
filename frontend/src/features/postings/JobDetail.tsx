import { For } from "solid-js";
import { jobCard, jobDetail } from "../../relay/DiscoveryOperations";
import { useFragment } from "../../relay/fragments";
import type { DiscoveryOperations_jobDetail$key } from "../../__generated__/DiscoveryOperations_jobDetail.graphql";
import type { DiscoveryOperations_job$key } from "../../__generated__/DiscoveryOperations_job.graphql";
import { Link } from "../../ui/Link";
import { Badge } from "../../ui/Badge";
import "../discovery/details.css";

export function JobDetail(props: { job: DiscoveryOperations_jobDetail$key }) {
  const detail = useFragment(jobDetail, () => props.job);
  const job = useFragment<DiscoveryOperations_job$key>(jobCard, detail);
  const employment: Record<string, string> = { FULL_TIME: "정규직", PART_TIME: "시간제", CONTRACT: "계약직", INTERNSHIP: "인턴", UNKNOWN: "미분류" };
  const remote: Record<string, string> = { REMOTE: "원격", HYBRID: "하이브리드", ONSITE: "출근", UNKNOWN: "미분류" };
  const groups = [{ level: "REQUIRED", label: "필수 기술" }, { level: "PREFERRED", label: "우대 기술" }, { level: "MENTIONED", label: "언급된 기술" }];
  return <main class="detail-page">
    <Link href="/jobs">← 채용 공고</Link>
    <header><Link href={`/companies/${encodeURIComponent(job().careerSite.slug)}`}>{job().careerSite.displayName}</Link>
      <h1>{job().title}</h1><Badge>{job().status === "OPEN" ? "채용 중" : "마감"}</Badge>
      <dl class="detail-metadata">
        <div><dt>고용 형태</dt><dd>{employment[job().classification?.employment?.value ?? ""] ?? detail().employmentHint ?? "정보 없음"}</dd></div>
        <div><dt>근무 지역</dt><dd>{job().classification?.location?.displayName ?? detail().locationHint ?? "정보 없음"}</dd></div>
        <div><dt>근무 방식</dt><dd>{remote[job().classification?.remote?.value ?? ""] ?? detail().remoteHint ?? "정보 없음"}</dd></div>
      </dl>
      <Link class="detail-apply" href={job().canonicalUrl} target="_blank">지원하기 · 외부 채용 사이트 · 새 창 ↗</Link>
    </header>
    <For each={groups}>{group => <section aria-label={group.label}><h2>{group.label}</h2>
      <ul class="detail-links"><For each={job().classification?.skills.filter(skill => skill.level === group.level) ?? []}>{item => <li>{item.skill
        ? <Link href={`/skills/${encodeURIComponent(item.skill.slug)}`}>{item.skill.displayName}</Link> : <Badge>{item.text}</Badge>}</li>}</For></ul>
      {!(job().classification?.skills.some(skill => skill.level === group.level)) && <p>표시된 기술이 없어요.</p>}
    </section>}</For>
    <section aria-label="공고 내용"><h2>공고 내용</h2><p class="detail-description">{detail().descriptionText}</p></section>
    <dl class="detail-metadata"><For each={[
      ["최초 확인", detail().firstSeenAt], ["최근 확인", detail().lastSeenAt], ["업데이트", detail().sourceUpdatedAt ?? job().updatedAt], ["마감일", detail().closedAt],
    ]}>{([label, value]) => value ? <div><dt>{label}</dt><dd><time datetime={value}>{value.slice(0, 10)}</time></dd></div> : null}</For></dl>
  </main>;
}

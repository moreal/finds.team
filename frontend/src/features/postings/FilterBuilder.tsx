import { createUniqueId, For } from "solid-js";
import { Button } from "../../ui/Button";
import { TextField } from "../../ui/TextField";
import { parseJobSearch, serializeJobSearch } from "./filterCodec";
import type { JobSearchState, ParseResult } from "./filterSchema";

export function jobSearchFromForm(data: FormData): ParseResult {
  const params = new URLSearchParams();
  for (const [key, value] of data) if (typeof value === "string" && value.trim()) params.append(key, value);
  return parseJobSearch(params.toString());
}

const choices = [
  ["role", "직무", ["backend", "frontend", "fullstack", "mobile", "data", "devops", "design", "product", "qa", "unknown"], ["백엔드", "프런트엔드", "풀스택", "모바일", "데이터", "DevOps", "디자인", "프로덕트", "QA", "미분류"]],
  ["employment", "고용 형태", ["full-time", "part-time", "contract", "internship", "unknown"], ["정규직", "시간제", "계약직", "인턴", "미분류"]],
  ["remote", "원격 근무", ["remote", "hybrid", "onsite", "unknown"], ["원격", "하이브리드", "출근", "미분류"]],
  ["updated", "업데이트", ["24h", "7d", "30d"], ["최근 24시간", "최근 7일", "최근 30일"]],
  ["order", "정렬", ["updated-desc"], ["최근 업데이트순"]],
] as const;

export function FilterBuilder(props: { state: JobSearchState; onApply?: (result: ParseResult) => void }) {
  const id = createUniqueId();
  const params = () => new URLSearchParams(serializeJobSearch(props.state));
  return <form method="get" action="/jobs" class="jobs-filter" onSubmit={(event) => {
    if (!props.onApply) return;
    event.preventDefault();
    props.onApply(jobSearchFromForm(new FormData(event.currentTarget)));
  }}>
    <TextField name="q" label="검색어" value={props.state.text ?? ""} placeholder="직무, 기술, 회사" />
    <For each={[...params().getAll("skill"), ""]}>{skill => <TextField name="skill" label={skill ? "기술 조건" : "기술 추가"}
      value={skill} hint="예: kotlin:required · 제외: -java · 우대: python:preferred" />}</For>
    {choices.map(([name, label, values, labels]) => <div class="ui-field">
      <label for={`${id}-${name}`}>{label}</label>
      <select id={`${id}-${name}`} name={name} class="ui-input" value={params().get(name) ?? ""}>
        <option value="">{name === "order" ? "최근 업데이트순 (기본)" : "전체"}</option>
        {values.map((value, index) => <option value={value} selected={params().get(name) === value}>{labels[index]}</option>)}
      </select>
    </div>)}
    <TextField name="site" label="회사 ID" value={props.state.siteId ?? ""} />
    <Button type="submit">필터 적용</Button>
  </form>;
}

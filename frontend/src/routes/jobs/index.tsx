import { createFileRoute, useRouter } from "@tanstack/solid-router";
import { createSignal, For, onSettled } from "solid-js";
import { FilterBuilder } from "../../features/postings/FilterBuilder";
import { JobsConnection } from "../../features/postings/JobsConnection";
import { loadJobsPage } from "../../features/postings/JobsPageQuery";
import { parseJobSearch } from "../../features/postings/filterCodec";
import { Dialog } from "../../ui/Dialog";
import { Link } from "../../ui/Link";
import { AsyncState } from "../../ui/AsyncState";
import "./jobs.css";

function searchString(search: Record<string, unknown>) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(search)) {
    for (const entry of Array.isArray(value) ? value : [value]) params.append(key, String(entry));
  }
  return params.toString();
}

export const Route = createFileRoute("/jobs/")({
  validateSearch: (search: Record<string, unknown>) => {
    const validated = new URLSearchParams(parseJobSearch(searchString(search)).canonicalSearch);
    const values: Record<string, string | string[]> = {};
    for (const key of new Set(validated.keys())) {
      const entries = validated.getAll(key);
      values[key] = entries.length > 1 ? entries : entries[0];
    }
    return values;
  },
  loaderDeps: ({ search }) => ({ canonicalSearch: parseJobSearch(searchString(search)).canonicalSearch }),
  loader: ({ context, location }) => loadJobsPage(context.relayEnvironment, location.searchStr),
  staleTime: Infinity,
  head: ({ loaderData }) => ({
    meta: [{ title: "채용 공고 | finds.team" }, { name: "description", content: "직무, 기술, 회사와 근무 방식으로 나에게 맞는 채용 공고를 찾아보세요." }, ...(loaderData?.noindex ? [{ name: "robots", content: "noindex, follow" }] : [])],
    links: [{ rel: "canonical", href: `https://finds.team/jobs${loaderData?.canonicalSearch ?? ""}` }],
  }),
  pendingComponent: () => <main class="jobs-page"><h1>채용 공고</h1><AsyncState state="pending" /></main>,
  component: JobsPage,
});

function JobsPage() {
  const result = Route.useLoaderData();
  const router = useRouter();
  const [open, setOpen] = createSignal(false);
  const [corrections] = createSignal(result().corrections.join(" "));
  const constraints = () => [...new URLSearchParams(result().canonicalSearch)].filter(([key]) => key !== "order");
  const labels: Record<string, string> = { q: "검색어", skill: "기술", role: "직무", employment: "고용 형태", remote: "원격 근무", site: "회사", updated: "업데이트" };
  function remove(index: number) {
    const params = new URLSearchParams(result().canonicalSearch);
    const entries = [...params];
    const target = constraints()[index];
    const next = new URLSearchParams(entries.filter(([key, value]) => key !== target[0] || value !== target[1])).toString();
    return `/jobs${next ? `?${next}` : ""}`;
  }
  function navigate(search: string) {
    setOpen(false);
    void router.navigate({ href: `/jobs${search}`, resetScroll: false });
  }
  onSettled(() => {
    if (window.location.search !== result().canonicalSearch) {
      // Repair the address without reloading the already validated operation.
      router.history.replace(`/jobs${result().canonicalSearch}`, router.history.location.state);
    }
  });
  return <main class="jobs-page">
    <header class="jobs-header"><Link href="/jobs">finds.team</Link><h1>다음 기회를 발견하세요.</h1><p>관심 있는 기술과 일하는 방식으로 채용 공고를 찾아보세요.</p></header>
    <p role="status" aria-live="polite" class="jobs-correction">{corrections()}</p>
    <div class="jobs-mobile-filter"><Dialog trigger="필터 열기" title="공고 필터" closeLabel="닫기" open={open()} onOpenChange={setOpen}>
      <FilterBuilder state={result().state} onApply={navigate} />
    </Dialog></div>
    <div class="jobs-layout">
      <aside class="jobs-desktop-filter" aria-label="공고 필터"><h2>필터</h2><FilterBuilder state={result().state} onApply={navigate} /></aside>
      <div class="jobs-results">
        <ul class="jobs-constraints" aria-label="적용한 조건"><For each={constraints()}>{([key, value], index) => <li><Link href={remove(index())}>{labels[key] ?? key}: {value} 해제</Link></li>}</For></ul>
        <JobsConnection variables={result().variables} failure={result().failure} constraints={constraints().map(([key, value]) => `${labels[key]}: ${value}`).join(" · ")} onClear={() => navigate("")} />
      </div>
    </div>
  </main>;
}

import { renderToString } from "@solidjs/web";
import { JSDOM } from "jsdom";
import { expect, it } from "vitest";
import { Dialog } from "../Dialog";
import { Select } from "../Select";
import { Combobox } from "../Combobox";
import { Popover } from "../Popover";
import { Toast } from "../Toast";
import { Tabs } from "../Tabs";
import { Tooltip } from "../Tooltip";
import { PostingCard } from "../../features/postings/PostingCard";
import { ConnectionList } from "../../features/postings/ConnectionList";
import { CrawlStatusCell } from "../../features/admin/CrawlStatusCell";
import { PasskeyList } from "../../features/security/PasskeyList";
import { AuditTimeline } from "../../features/admin/AuditTimeline";

const documentFor = (render: Parameters<typeof renderToString>[0]) => new JSDOM(renderToString(render, { manifest: {} })).window.document;
const options = [{ value: "solid", label: "Solid" }, { value: "rust", label: "Rust" }];

it("keeps native dialog and select semantics through the product boundary", () => {
  const doc = documentFor(() => <><Dialog trigger="열기" title="설정" closeLabel="닫기" /><Select label="기술" options={options} value={options[1]} getOptionValue={o => o.value} getOptionLabel={o => o.label} onChange={() => {}} /></>);
  expect(doc.querySelector("button")?.getAttribute("aria-haspopup")).toBe("dialog");
  expect(doc.querySelector("dialog")?.hasAttribute("open")).toBe(false);
  expect(doc.querySelector("select")?.value).toBe("rust");
});

it("labels comboboxes and hides inactive popup content during SSR", () => {
  const doc = documentFor(() => <><Combobox label="기술 검색" options={options} value="rust" onChange={() => {}} /><Popover trigger="필터" title="필터 설정"><button>적용</button></Popover><Tooltip label="도움말" content="검색 안내" /></>);
  const input = doc.querySelector<HTMLInputElement>('[role="combobox"]')!;
  expect(input.labels?.[0].textContent).toBe("기술 검색");
  expect(input.value).toBe("Rust");
  expect(input.getAttribute("aria-expanded")).toBe("false");
  expect(doc.querySelector('[role="listbox"]')).toBeNull();
  expect(doc.querySelector('[role="tooltip"]')).toBeNull();
});

it("associates tabs with their panels and exposes only the selected panel", () => {
  const doc = documentFor(() => <Tabs label="보기" value="jobs" onChange={() => {}} tabs={[{ value: "jobs", label: "공고", content: "공고 목록" }, { value: "companies", label: "회사", content: "회사 목록" }]} />);
  const tabs = doc.querySelectorAll('[role="tab"]');
  expect(tabs[0].getAttribute("tabindex")).toBe("0");
  expect(tabs[1].getAttribute("tabindex")).toBe("-1");
  expect(doc.getElementById(tabs[0].getAttribute("aria-controls")!)?.textContent).toBe("공고 목록");
  expect(doc.getElementById(tabs[1].getAttribute("aria-controls")!)?.hidden).toBe(true);
});

it("announces notification and pagination state without removing existing postings", () => {
  const doc = documentFor(() => <><Toast message="저장했어요" onDismiss={() => {}} /><ConnectionList state="pagination-pending" items={["기존 공고"]} renderItem={item => <span>{item}</span>} hasNextPage onLoadMore={() => {}} /></>);
  expect(doc.querySelector('[role="status"]')?.textContent).toContain("저장했어요");
  expect(doc.body.textContent).toContain("기존 공고");
  expect(Array.from(doc.querySelectorAll("button")).find(b => b.textContent?.includes("불러오는"))?.disabled).toBe(true);
});

it("renders filtered empty and recoverable connection errors", () => {
  const empty = documentFor(() => <ConnectionList state="filtered-empty" items={[]} renderItem={() => null} constraints="서울" onClearFilters={() => {}} />);
  expect(empty.body.textContent).toContain("서울");
  expect(empty.querySelector("button")?.textContent).toContain("조건 해제");
  const error = documentFor(() => <ConnectionList state="error" items={[]} renderItem={() => null} correlationId="req-42" onRetry={() => {}} />);
  expect(error.body.textContent).toContain("req-42");
  expect(error.querySelector("button")?.textContent).toContain("다시 시도");
});

it("renders navigable posting metadata and textual crawl status", () => {
  const doc = documentFor(() => <><PostingCard title="개발자" href="/jobs/1" company="핀즈" companyHref="/companies/finds" metadata={["서울", "원격"]} skills={["Solid"]} /><CrawlStatusCell status="failed" count={12} updatedAt="2026-09-22T00:00:00Z" timeLabel="9월 22일" /></>);
  expect(doc.querySelector("h2 a")?.getAttribute("href")).toBe("/jobs/1");
  expect(doc.body.textContent).toContain("서울");
  expect(doc.body.textContent).toContain("실패");
  expect(doc.querySelector("time")?.dateTime).toBe("2026-09-22T00:00:00Z");
});

it("disables passkey mutations while busy and renders domain-specific empty states", () => {
  const doc = documentFor(() => <PasskeyList items={[{ id: "1", name: "노트북", createdAt: "2026-09-22", createdLabel: "9월 22일" }]} pendingId="1" onRemove={() => {}} />);
  expect(doc.querySelector("button")?.disabled).toBe(true);
  expect(doc.querySelector('[role="status"]')?.textContent).toContain("삭제");
  const empty = documentFor(() => <><PasskeyList items={[]} /><AuditTimeline items={[]} /></>);
  expect(empty.body.textContent).toContain("Passkey");
  expect(empty.body.textContent).toContain("감사 기록");
});

it("keeps audit actor, action, target, and machine-readable time together", () => {
  const doc = documentFor(() => <AuditTimeline items={[{ id: "a", actor: "관리자", action: "수집 요청", target: "회사 A", occurredAt: "2026-09-22T00:00:00Z", timeLabel: "9월 22일" }]} />);
  expect(doc.querySelector("li")?.textContent).toContain("관리자");
  expect(doc.querySelector("li")?.textContent).toContain("회사 A");
  expect(doc.querySelector("time")?.dateTime).toBe("2026-09-22T00:00:00Z");
});

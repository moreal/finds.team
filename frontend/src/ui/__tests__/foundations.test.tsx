import { renderToString } from "@solidjs/web";
import { JSDOM } from "jsdom";
import { describe, expect, it } from "vitest";
import { Button } from "../Button";
import { Link } from "../Link";
import { TextField } from "../TextField";
import { Badge } from "../Badge";
import { IconButton } from "../IconButton";
import { Skeleton } from "../Skeleton";
import { AsyncState, type AsyncStateKind } from "../AsyncState";

function documentFor(render: Parameters<typeof renderToString>[0]) {
  return new JSDOM(renderToString(render, { manifest: {} })).window.document;
}

describe("native foundation accessibility", () => {
  it("prevents submission by default and duplicate activation while loading", () => {
    const document = documentFor(() => <><Button>저장</Button><Button loading>저장 중</Button><Button disabled>사용 불가</Button></>);
    const buttons = document.querySelectorAll("button");
    expect(buttons[0].type).toBe("button");
    expect(buttons[0].disabled).toBe(false);
    expect(buttons[1].disabled).toBe(true);
    expect(buttons[1].getAttribute("aria-busy")).toBe("true");
    expect(buttons[1].textContent).toContain("저장 중");
    expect(buttons[2].disabled).toBe(true);
  });

  it("associates unique labels, hints, consumer descriptions and validation errors", () => {
    const document = documentFor(() => <><TextField label="이메일" hint="업무 이메일" error="이메일을 확인하세요" aria-describedby="policy" /><TextField label="이름" /></>);
    const [input, second] = document.querySelectorAll("input");
    expect(input.labels?.[0].textContent).toBe("이메일");
    expect(input.id).not.toBe(second.id);
    expect(input.getAttribute("aria-invalid")).toBe("true");
    const descriptions = input.getAttribute("aria-describedby")!.split(" ");
    expect(descriptions).toContain("policy");
    expect(descriptions.map((id) => document.getElementById(id)?.textContent)).toContain("업무 이메일");
    expect(descriptions.map((id) => document.getElementById(id)?.textContent)).toContain("이메일을 확인하세요");
    expect(second.hasAttribute("aria-describedby")).toBe(false);
  });

  it("keeps link semantics and protects new windows", () => {
    const document = documentFor(() => <Link href="https://example.com" target="_blank" rel="external">지원하기</Link>);
    const link = document.querySelector("a")!;
    expect(link.getAttribute("href")).toBe("https://example.com");
    expect(link.rel.split(" ")).toEqual(expect.arrayContaining(["external", "noopener", "noreferrer"]));
    expect(link.textContent).toContain("지원하기");
  });

  it("names icon controls and presents textual statuses with decorative placeholders hidden", () => {
    const document = documentFor(() => <><IconButton label="필터 닫기"><span>×</span></IconButton><Badge tone="warning">지연</Badge><Skeleton /></>);
    expect(document.querySelector("button")?.getAttribute("aria-label")).toBe("필터 닫기");
    expect(document.querySelector(".ui-badge")?.textContent).toBe("지연");
    expect(document.querySelector(".ui-skeleton")?.getAttribute("aria-hidden")).toBe("true");
  });
});

describe("async surface contract", () => {
  it.each<AsyncStateKind>(["pending", "pagination-pending", "empty", "filtered-empty", "error", "unauthorized", "forbidden", "data"])("renders %s without losing existing pagination data", (state) => {
    const document = documentFor(() => <AsyncState state={state} constraints="서울 · 원격" correlationId="request-123" onRetry={() => {}} onClearFilters={() => {}}><p>기존 공고</p></AsyncState>);
    expect(document.body.textContent?.includes("기존 공고")).toBe(["data", "pagination-pending"].includes(state));
    if (state === "empty") expect(document.body.textContent).toContain("아직 공고가 없어요");
    if (state === "unauthorized") expect(document.body.textContent).toContain("로그인이 필요해요");
    if (state === "forbidden") expect(document.body.textContent).toContain("접근 권한이 없어요");
    if (state === "filtered-empty") {
      expect(document.body.textContent).toContain("서울 · 원격");
      expect(document.querySelector("button")?.textContent).toContain("조건 해제");
    }
    if (state === "error") {
      expect(document.body.textContent).toContain("request-123");
      expect(document.querySelector("button")?.textContent).toContain("다시 시도");
    }
    if (state.endsWith("pending")) expect(document.querySelector('[role="status"]')).not.toBeNull();
  });
});

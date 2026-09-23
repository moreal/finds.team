import { createRoute } from "@tanstack/solid-router";
import { createSignal } from "solid-js";
import { Button } from "../../ui/Button";
import { Link } from "../../ui/Link";
import { TextField } from "../../ui/TextField";
import { Badge } from "../../ui/Badge";
import { IconButton } from "../../ui/IconButton";
import { Skeleton } from "../../ui/Skeleton";
import { AsyncState } from "../../ui/AsyncState";
import { Dialog } from "../../ui/kobalte/Dialog";
import { Route as rootRoute } from "../__root";
import "./ui.css";

// Explicitly typed against the root because production generation excludes
// this route from FileRoutesByPath. __dev is pathless, so the URL is /ui.
export const Route = createRoute({ getParentRoute: () => rootRoute, path: "/ui", component: UiCatalog });

function UiCatalog() {
  const [count, setCount] = createSignal(128);
  const [loading, setLoading] = createSignal(false);
  return <main class="ui-catalog">
    <header><p class="ui-muted">finds.team · 개발 전용</p><h1>컴포넌트 카탈로그</h1><p>따뜻한 종이, 선명한 글자, 명확한 행동.</p></header>
    <section aria-labelledby="actions-title"><h2 id="actions-title">버튼과 링크</h2>
      <div class="catalog-row"><Button>공고 보기</Button><Button variant="secondary">필터 적용</Button><Button variant="ghost">초기화</Button><Button variant="danger">패스키 삭제</Button><Button disabled>사용 불가</Button><Button loading>저장 중</Button><Button size="dense">간결한 버튼</Button><IconButton label="필터 닫기">×</IconButton><Link href="https://example.com" target="_blank">채용 페이지</Link></div>
      <div class="catalog-row"><Button variant="secondary">새로운 기회를 발견하기 위해 선택한 모든 검색 조건을 적용하기</Button></div>
      <p class="ui-muted">Tab으로 초점을 이동하고 버튼을 눌러 상호작용 상태를 확인하세요.</p>
      <div class="catalog-confirm"><Dialog trigger="최종 삭제 확인 예시" title="패스키를 삭제할까요?" description="이 작업은 되돌릴 수 없어요." closeLabel="취소"><Button variant="danger-confirm">삭제 확인</Button></Dialog></div>
    </section>
    <section aria-labelledby="fields-title"><h2 id="fields-title">입력 필드</h2><div class="catalog-grid">
      <TextField label="검색어" placeholder="직무, 기술, 회사" hint="관심 있는 키워드를 입력하세요." />
      <TextField label="이메일" type="email" autocomplete="email" hint="업무용 이메일을 입력하세요." error="이메일 형식을 확인하세요." value="잘못된 이메일" />
      <TextField label="사용할 수 없는 입력" disabled value="잠긴 값" />
    </div></section>
    <section aria-labelledby="status-title"><h2 id="status-title">상태와 숫자</h2><div class="catalog-row"><Badge>대기</Badge><Badge tone="action">신규</Badge><Badge tone="success">완료</Badge><Badge tone="warning">지연</Badge><Badge tone="danger">실패</Badge><output class="ui-tabular" aria-label="공고 수">{count()}</output><Button variant="secondary" onClick={() => setCount((value) => value + 1)}>공고 수 늘리기</Button></div></section>
    <section aria-labelledby="skeleton-title"><h2 id="skeleton-title">스켈레톤</h2><div class="catalog-grid"><Skeleton shape="card" /><div class="catalog-stack"><Skeleton shape="circle" /><Skeleton /><Skeleton /></div></div></section>
    <section aria-labelledby="async-title"><h2 id="async-title">데이터 상태</h2><div class="catalog-grid">
      <article><h3>처음 불러오기</h3><AsyncState state="pending" /></article>
      <article><h3>더 불러오기</h3><AsyncState state="pagination-pending"><p>기존 공고는 계속 표시됩니다.</p></AsyncState></article>
      <article><h3>비어 있음</h3><AsyncState state="empty" /></article>
      <article><h3>검색 결과 없음</h3><AsyncState state="filtered-empty" constraints="서울 · 원격 근무" onClearFilters={() => setCount(128)} /></article>
      <article><h3>오류</h3><AsyncState state={loading() ? "pending" : "error"} correlationId="demo-2026" onRetry={() => setLoading(true)} /></article>
      <article><h3>로그인 필요</h3><AsyncState state="unauthorized" /></article>
      <article><h3>권한 없음</h3><AsyncState state="forbidden" /></article>
      <article><h3>데이터 표시</h3><AsyncState state="data"><p>프런트엔드 개발자 · 서울</p></AsyncState></article>
    </div></section>
  </main>;
}

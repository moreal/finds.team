import { createFileRoute } from "@tanstack/solid-router";

export const Route = createFileRoute("/")({
  component: Home,
});

function Home() {
  return (
    <main>
      <h1>finds.team</h1>
      <p>좋은 일자리를 발견하세요.</p>
      <a href="/jobs">공고 보기</a>
    </main>
  );
}

import { createFileRoute, redirect } from "@tanstack/solid-router";

export const Route = createFileRoute("/")({
  beforeLoad: () => { throw redirect({ href: "/jobs", statusCode: 308 }); },
});

import { createServer } from "node:http";
import { detailData } from "./discovery-data.ts";

const requests: { variables: any }[] = [];
createServer(async (req, res) => {
  if (req.url === "/__requests") {
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify(requests));
    return;
  }
  if (req.url === "/graphql") {
    let body = "";
    for await (const chunk of req) body += chunk;
    const { variables, operationName } = JSON.parse(body);
    if (operationName !== "DiscoveryOperationsJobsQuery" && ["unavailable", "unauthorized", "forbidden"].includes(variables.slug ?? variables.id)) {
      const value = variables.slug ?? variables.id;
      res.writeHead(value === "unauthorized" ? 401 : value === "forbidden" ? 403 : 503); res.end(); return;
    }
    if (variables.slug === "pagination-failure" && variables.after) { res.writeHead(503); res.end(); return; }
    const detail = detailData(operationName, variables);
    if (detail) {
      if (variables.after || variables.companiesAfter || variables.relatedAfter) await new Promise(resolve => setTimeout(resolve, 300));
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ data: detail }));
      return;
    }
    requests.push({ variables });
    const text = variables.filter?.all?.find((entry: any) => entry.textContains)?.textContains ?? "";
    if (text === "network-failure") { res.writeHead(503); res.end(); return; }
    if (text === "pagination-failure" && variables.after) { res.writeHead(503); res.end(); return; }
    if (text === "unauthorized" || text === "forbidden") { res.writeHead(text === "unauthorized" ? 401 : 403); res.end(); return; }
    const validationCode = ["INVALID_FILTER", "UNKNOWN_SKILL", "INVALID_INPUT"].includes(text) ? text : text === "invalid" ? "INVALID_FILTER" : undefined;
    if (validationCode || (text === "retry-connection" && requests.filter(entry => entry.variables.filter?.all?.some((part: any) => part.textContains === text)).length === 1)) {
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ data: { jobPostings: { edges: [], totalCount: 0, error: { code: validationCode ?? "INTERNAL", message: "Private diagnostic" }, pageInfo: { hasNextPage: false, hasPreviousPage: false, startCursor: null, endCursor: null } } } }));
      return;
    }
    const empty = text === "nothing" || text === "empty" || req.headers.cookie?.includes("jobs-empty=1");
    const start = variables.after ? Number(variables.after.slice(7)) + 1 : 1;
    const total = text === "large" ? 62 : 22;
    const edges = empty ? [] : Array.from({ length: Math.min(20, total - start + 1) }, (_, index) => {
      const id = start + index;
      return { cursor: `cursor-${id}`, node: {
        __typename: "JobPosting", id: `job-${id}`, title: `Backend engineer ${id}`, canonicalUrl: `https://example.com/jobs/${id}`,
        status: "OPEN", updatedAt: "2026-09-20T00:00:00Z",
        careerSite: { id: "site-1", slug: "acme", displayName: "Acme" },
        classification: { taxonomyVersion: 1, role: { value: "BACKEND", rawValue: null }, employment: { value: "FULL_TIME", rawValue: null },
          remote: { value: "REMOTE", rawValue: null }, location: { displayName: "Seoul", searchValue: "seoul" },
          skills: [{ skill: { id: "skill-1", slug: "kotlin", displayName: "Kotlin" }, text: "Kotlin", level: "REQUIRED" }] },
      } };
    });
    if (variables.after) await new Promise(resolve => setTimeout(resolve, 600));
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ data: { jobPostings: { edges, totalCount: empty ? 0 : total, error: null,
      pageInfo: { hasNextPage: !empty && start + edges.length <= total, hasPreviousPage: !!variables.after,
        startCursor: edges[0]?.cursor ?? null, endCursor: edges.at(-1)?.cursor ?? null } } } }));
    return;
  }
  const upstream = await fetch(`http://127.0.0.1:4175${req.url}`, { headers: { host: "127.0.0.1:4176", cookie: req.headers.cookie ?? "" }, redirect: "manual" });
  res.writeHead(upstream.status, Object.fromEntries(upstream.headers));
  res.end(Buffer.from(await upstream.arrayBuffer()));
}).listen(4176, "127.0.0.1");

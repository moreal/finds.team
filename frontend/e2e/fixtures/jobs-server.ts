import { createServer } from "node:http";
import { detailData } from "./discovery-data.ts";

const requests: { variables: any }[] = [];
const pendingRegistrations = new Map<string, string>();
createServer(async (req, res) => {
  const account = /(?:^|;\s*)security-account=([^;]+)/.exec(req.headers.cookie ?? '')?.[1];
  if (req.url === '/auth/csrf') {
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ token: 'fixture-csrf', headerName: 'X-CSRF-TOKEN' })); return;
  }
  if (['/webauthn/register/begin', '/webauthn/register/cancel'].includes(req.url ?? '')) {
    const key = req.headers['idempotency-key'];
    const pending = account ? pendingRegistrations.get(account) : undefined;
    const status = req.method !== 'POST' ? 405 : !account ? 401 : req.headers['x-csrf-token'] !== 'fixture-csrf' || account === 'stale' ? 403
      : typeof key !== 'string' ? 400 : pending && pending !== key ? 409 : 200;
    res.writeHead(status, { 'content-type': 'application/json' });
    if (status !== 200) { res.end(JSON.stringify({ status })); return; }
    if (req.url!.endsWith('/begin')) pendingRegistrations.set(account!, key as string);
    else pendingRegistrations.delete(account!);
    res.end(JSON.stringify(req.url!.endsWith('/begin') ? { ready: true } : { success: true })); return;
  }
  if (req.url === "/__requests") {
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify(requests));
    return;
  }
  if (req.url === "/graphql") {
    let body = "";
    for await (const chunk of req) body += chunk;
    const { variables, operationName } = JSON.parse(body);
    if (operationName === 'AccountOperationsViewerQuery') {
      const account = /(?:^|;\s*)security-account=([^;]+)/.exec(req.headers.cookie ?? '')?.[1];
      if (account === 'restricted' || (account && pendingRegistrations.has(account))) { res.writeHead(403); res.end(); return; }
      res.setHeader('content-type', 'application/json');
      const connection = (nodes: object[]) => ({ edges: nodes.map((node: any) => ({ cursor: node.id, node })), totalCount: nodes.length, error: null,
        pageInfo: { hasNextPage: false, hasPreviousPage: false, startCursor: (nodes[0] as any)?.id ?? null, endCursor: (nodes.at(-1) as any)?.id ?? null } });
      res.end(JSON.stringify({ data: { viewer: account ? {
        user: { id: account, roles: ['USER'] },
        passkeys: connection([{ __typename: 'Passkey', id: 'key-1', label: account === 'user-1' ? 'Laptop' : `${account} laptop`, createdAt: '2026-09-20T00:00:00Z', lastUsedAt: null },
          { __typename: 'Passkey', id: 'key-2', label: 'Backup', createdAt: '2026-09-20T00:00:00Z', lastUsedAt: null }]),
        sessions: { ...connection([{ __typename: 'Session', id: 'session-1', createdAt: '2026-09-20T00:00:00Z', expiresAt: '2026-09-25T00:00:00Z', current: true },
          { __typename: 'Session', id: 'session-2', createdAt: '2026-09-20T00:00:00Z', expiresAt: '2026-09-25T00:00:00Z', current: false }]), error: account === 'private-semantic' ? { code: 'FORBIDDEN', message: 'private diagnostic' } : null },
      } : null } }));
      return;
    }
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

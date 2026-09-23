export const adminRequests: { operation: string; role: string; variables: any }[] = [];
const sites = [
  { id: 'healthy', slug: 'healthy', displayName: 'Healthy source', outcome: 'SUCCESS', finishedAt: '2099-09-24T00:00:00Z' },
  { id: 'running', slug: 'running', displayName: 'Running source', outcome: null, finishedAt: null },
  { id: 'stale', slug: 'stale', displayName: 'Stale source', outcome: 'SUCCESS', finishedAt: '2020-01-01T00:00:00Z' },
  { id: 'failed', slug: 'failed', displayName: 'Failed source', outcome: 'FAILED', finishedAt: '2026-09-24T00:00:00Z' },
];
function connection(nodes: any[]) { return { edges: nodes.map(node => ({ cursor: node.id ?? node.careerSiteId, node })), totalCount: nodes.length, error: null, pageInfo: { hasNextPage: false, hasPreviousPage: false, startCursor: null, endCursor: null } }; }
export function adminData(operation: string, variables: any, cookie: string) {
  if (!operation.startsWith('AdminOperations')) return undefined;
  const role = /(?:^|;\s*)admin-role=([^;]+)/.exec(cookie)?.[1] ?? '';
  adminRequests.push({ operation, role, variables });
  if (operation === 'AdminOperationsViewerQuery') return { viewer: role ? { user: { __typename: 'User', id: 'admin-user', roles: [role] } } : null };
  if (operation === 'AdminOperationsSitesQuery') {
    const paginated = cookie.includes('admin-fault=pagination');
    const selected = paginated ? variables.after ? sites.slice(1) : sites.slice(0, 1) : sites;
    const data = connection(selected.map(s => ({ __typename: 'CareerSite', ...s, canonicalBaseUrl: 'https://example.com/jobs', provider: 'FLEX', crawlSummary: { outcome: s.outcome, finishedAt: s.finishedAt } })));
    if (paginated && !variables.after) Object.assign(data.pageInfo, { hasNextPage: true, endCursor: 'healthy' });
    return { careerSites: data };
  }
  if (operation === 'AdminOperationsStatusesQuery') return { crawlStatuses: connection(sites.map(s => ({ __typename: 'CrawlStatus', careerSiteId: s.id, runId: `${s.id}-run`, outcome: s.outcome, finishedAt: s.finishedAt, error: null }))) };
  if (operation === 'AdminOperationsHistoryQuery') {
    const site = sites.find(s => s.id === variables.slug);
    return { careerSite: site ? { ...site, canonicalBaseUrl: 'https://example.com/jobs', provider: 'FLEX', crawlSummary: { outcome: site.outcome, finishedAt: site.finishedAt }, crawlHistory: connection([{ __typename: 'CrawlRun', id: 'run-1', careerSiteId: site.id, startedAt: '2026-09-20T00:00:00Z', finishedAt: '2026-09-20T00:01:00Z', outcome: 'FAILED', counts: { fetched: 10, inserted: 2, updated: 3, touched: 5, missing: 0, closed: 0, reopened: 0 }, error: { code: 'CRAWL_FAILED', message: 'Private diagnostics' } }]) } : null };
  }
  if (operation === 'AdminOperationsAuditQuery') return { auditEvents: connection([{ __typename: 'AuditEvent', id: 'audit-1', occurredAt: '2026-09-20T00:00:00Z', actorKind: 'USER', actorUserId: 'actor-1', action: 'MANUAL_CRAWL_TRIGGERED', targetType: 'CAREER_SITE', targetId: 'site-1', outcome: 'SUCCEEDED', details: { role: null, provider: null, enabled: null } }]) };
}

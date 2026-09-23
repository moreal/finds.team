import RelayRuntime, { type Environment, type GraphQLTaggedNode, type Variables, type MutableRecordSource } from 'relay-runtime';
import { redirect } from '@tanstack/solid-router';
import * as operations from '../../relay/AdminOperations';
import { readQuery } from '../../relay/fragments';
import { accountFailure, type AccountFailure } from '../security/AccountPageQuery';
import type { AdminOperationsViewerQuery } from '../../__generated__/AdminOperationsViewerQuery.graphql';
import type { AdminOperationsSitesQuery } from '../../__generated__/AdminOperationsSitesQuery.graphql';
import type { AdminOperationsStatusesQuery } from '../../__generated__/AdminOperationsStatusesQuery.graphql';
import type { AdminOperationsHistoryQuery } from '../../__generated__/AdminOperationsHistoryQuery.graphql';
import type { AdminOperationsAuditQuery } from '../../__generated__/AdminOperationsAuditQuery.graphql';

export type AdminKind = 'dashboard' | 'sites' | 'detail' | 'audit';
export type AdminLoad = { kind: AdminKind; variables: Variables; now: number; failure?: AccountFailure };
export class AdminError extends Error { constructor(readonly status: number) { super('Admin request failed'); } }
export const queryFor = { dashboard: operations.statuses, sites: operations.sites, detail: operations.history, audit: operations.auditPage };
export async function checkAdmin(environment: Environment) {
  try {
    await fetchAdmin(environment, operations.viewer, {});
    const data = readQuery<AdminOperationsViewerQuery>(environment, operations.viewer, {});
    if (!data?.viewer) throw new AdminError(401);
    if (!data.viewer.user.roles.includes('ADMIN')) throw new AdminError(403);
    return data.viewer.user.id;
  } catch (error) {
    if ([401, 403].includes(adminFailure(error).status)) clearAdminRecords(environment);
    throw error;
  }
}
/** Operational records and their owned descendants are private even when detached by a later page. */
export function clearAdminRecords(environment: Environment) {
  const types = new Set(['CrawlRun', 'CrawlRunEdge', 'CrawlRunConnection', 'CrawlStatus', 'CrawlStatusEdge', 'CrawlStatusConnection', 'AuditEvent', 'AuditEventEdge', 'AuditEventConnection', 'AuditDetails']);
  const source = environment.getStore().getSource() as MutableRecordSource;
  const removed = new Set(source.getRecordIDs().filter(id => types.has(String(source.get(id)?.__typename))));
  const references = (value: unknown): string[] => value && typeof value === 'object' ? '__ref' in value ? [String(value.__ref)] : '__refs' in value && Array.isArray(value.__refs) ? value.__refs.filter((id): id is string => typeof id === 'string') : [] : [];
  for (const id of removed) for (const value of Object.values(source.get(id) ?? {})) for (const ref of references(value)) removed.add(ref);
  environment.commitUpdate(store => {
    for (const id of source.getRecordIDs()) {
      if (removed.has(id)) { store.delete(id); continue; }
      for (const [field, value] of Object.entries(source.get(id) ?? {})) {
        if (references(value).some(ref => removed.has(ref))) store.get(id)?.setValue(null, field);
      }
    }
  });
  for (const id of removed) source.remove(id);
}
export function adminFailure(error: unknown): AccountFailure { return error instanceof AdminError ? { status: error.status } : accountFailure(error); }
export function assertConnection(connection: { error?: { code: string } | null } | null | undefined) {
  if (!connection) throw new AdminError(404);
  if (connection.error) throw new AdminError(connection.error.code === 'FORBIDDEN' ? 403 : ['INVALID_INPUT', 'INVALID_FILTER', 'INVALID_CURSOR'].includes(connection.error.code) ? 400 : 500);
}
export async function fetchAdmin(environment: Environment, document: GraphQLTaggedNode, variables: Variables) {
  // Reject GraphQL semantic errors before normalizing any partial protected data.
  const request = RelayRuntime.getRequest(document);
  const result = await environment.getNetwork().execute(request.params, variables, {}).toPromise();
  if (!result || !('data' in result)) throw new AdminError(500);
  if ('errors' in result && result.errors?.length) {
    const codes = result.errors.map(error => (error as { extensions?: { code?: string } }).extensions?.code);
    throw new AdminError(codes.includes('FORBIDDEN') ? 403 : codes.includes('UNAUTHORIZED') ? 401 : codes.some(code => ['INVALID_INPUT', 'INVALID_FILTER', 'INVALID_CURSOR', 'INVALID_PAGE'].includes(String(code))) ? 400 : 500);
  }
  if (!result.data) throw new AdminError(500);
  const data = result.data as Record<string, any>;
  for (const connection of [data.careerSites, data.crawlStatuses, data.auditEvents, data.careerSite?.crawlHistory]) {
    if (connection?.error) {
      if (connection.error.code === 'FORBIDDEN') clearAdminRecords(environment);
      assertConnection(connection);
    }
  }
  environment.commitPayload(RelayRuntime.createOperationDescriptor(request, variables), result.data);
}
export function readAdmin(environment: Environment, kind: AdminKind, variables: Variables) {
  if (kind === 'dashboard') return readQuery<AdminOperationsStatusesQuery>(environment, operations.statuses, variables).crawlStatuses;
  if (kind === 'sites') return readQuery<AdminOperationsSitesQuery>(environment, operations.sites, variables).careerSites;
  if (kind === 'audit') return readQuery<AdminOperationsAuditQuery>(environment, operations.auditPage, variables).auditEvents;
  return readQuery<AdminOperationsHistoryQuery>(environment, operations.history, variables as { slug: string }).careerSite?.crawlHistory;
}
// The two connections have independent cursor scopes and may advance at different rates.
// Load relation pages until every visible row can be resolved, without borrowing its cursor.
export async function loadAdminRelations(environment: Environment, kind: 'dashboard' | 'sites') {
  const target = kind === 'dashboard' ? 'sites' : 'dashboard';
  const needed = kind === 'dashboard'
    ? readQuery<AdminOperationsStatusesQuery>(environment, operations.statuses, {}).crawlStatuses.edges.map(edge => edge.node.careerSiteId)
    : readQuery<AdminOperationsSitesQuery>(environment, operations.sites, {}).careerSites.edges.map(edge => edge.node.id);
  let current = readAdmin(environment, target, {});
  if (!current) { await fetchAdmin(environment, queryFor[target], {}); current = readAdmin(environment, target, {}); }
  assertConnection(current);
  while (current?.pageInfo.hasNextPage) {
    const ids = new Set(current.edges.map(edge => 'careerSiteId' in edge.node ? edge.node.careerSiteId : 'id' in edge.node ? edge.node.id : undefined));
    if (needed.every(id => ids.has(id))) break;
    const after = current.pageInfo.endCursor;
    await fetchAdmin(environment, queryFor[target], { after });
    const next = readAdmin(environment, target, { after });
    assertConnection(next);
    if (next?.pageInfo.hasNextPage && next.pageInfo.endCursor === after) throw new AdminError(500);
    current = next;
  }
}
export async function loadAdmin(environment: Environment, kind: AdminKind, variables: Variables = {}): Promise<AdminLoad> {
  const base = { kind, variables, now: Date.now() };
  try {
    await checkAdmin(environment);
  } catch (error) {
    const failure = adminFailure(error);
    if (failure.status === 401) throw redirect({ to: '/login' });
    return { ...base, failure };
  }
  try {
    await fetchAdmin(environment, queryFor[kind], variables);
    assertConnection(readAdmin(environment, kind, variables));
    if (kind === 'dashboard' || kind === 'sites') await loadAdminRelations(environment, kind);
    return base;
  } catch (error) {
    const failure = adminFailure(error);
    if ([401, 403].includes(failure.status)) clearAdminRecords(environment);
    return { ...base, failure };
  }
}

import RelayRuntime, { type Environment, type GraphQLTaggedNode, type Variables } from 'relay-runtime';
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
  await fetchAdmin(environment, operations.viewer, {});
  const data = readQuery<AdminOperationsViewerQuery>(environment, operations.viewer, {});
  if (!data?.viewer) throw new AdminError(401);
  if (!data.viewer.user.roles.includes('ADMIN')) throw new AdminError(403);
  return data.viewer.user.id;
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
  environment.commitPayload(RelayRuntime.createOperationDescriptor(request, variables), result.data);
}
export function readAdmin(environment: Environment, kind: AdminKind, variables: Variables) {
  if (kind === 'dashboard') return readQuery<AdminOperationsStatusesQuery>(environment, operations.statuses, variables).crawlStatuses;
  if (kind === 'sites') return readQuery<AdminOperationsSitesQuery>(environment, operations.sites, variables).careerSites;
  if (kind === 'audit') return readQuery<AdminOperationsAuditQuery>(environment, operations.auditPage, variables).auditEvents;
  return readQuery<AdminOperationsHistoryQuery>(environment, operations.history, variables as { slug: string }).careerSite?.crawlHistory;
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
    if (kind === 'dashboard') {
      await fetchAdmin(environment, operations.sites, {});
      assertConnection(readAdmin(environment, 'sites', {}));
    }
    return base;
  } catch (error) { return { ...base, failure: adminFailure(error) }; }
}

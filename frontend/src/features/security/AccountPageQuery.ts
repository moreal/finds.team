import RelayRuntime, { type Environment, type MutableRecordSource } from 'relay-runtime';
import type { AccountOperationsViewerQuery } from '../../__generated__/AccountOperationsViewerQuery.graphql';
import { viewer } from '../../relay/AccountOperations';
import { GraphQLRequestError } from '../../relay/network';

export type AccountFailure = { status: number; correlationId?: string };
export function accountFailure(error: unknown): AccountFailure {
  return error instanceof GraphQLRequestError ? { status: error.status, correlationId: error.correlationId } : { status: 500, correlationId: crypto.randomUUID() };
}
export function accountFailureMessage(failure: AccountFailure) {
  if (failure.status === 401) return '로그인이 필요해요. Passkey로 로그인해 주세요.';
  if (failure.status === 403) return '접근 권한이 없어요.';
  return `요청을 완료하지 못했어요. 다시 시도해 주세요.${failure.correlationId ? ` 문의 번호: ${failure.correlationId}` : ''}`;
}
/** SSR uses the request-owned environment; Start dehydrates its normalized records. */
export async function loadAccountPage(environment: Environment): Promise<{ failure?: AccountFailure }> {
  try {
    const data = await RelayRuntime.fetchQuery<AccountOperationsViewerQuery>(environment, viewer, {}, { fetchPolicy: 'network-only' }).toPromise();
    if (!data?.viewer) { clearAccountRecords(environment); return { failure: { status: 401 } }; }
    const errors = [data.viewer.passkeys.error, data.viewer.sessions.error].filter(Boolean);
    if (errors.length) {
      const status = errors.some(error => error?.code === 'FORBIDDEN') ? 403 : 500;
      if (status === 403) clearAccountRecords(environment);
      return { failure: { status } };
    }
    return {};
  } catch (error) {
    const failure = accountFailure(error);
    if (failure.status === 401 || failure.status === 403) clearAccountRecords(environment);
    return { failure };
  }
}

/** Remove protected account records when the session is no longer authorized. */
export function clearAccountRecords(environment: Environment) {
  // A refetch can detach old nodes/edges without removing their records. Scan
  // account-only schema types plus the viewer's synthetic-record namespace,
  // rather than following only the latest connection edges. Public discovery
  // types and their namespaces are deliberately outside this ownership scope.
  const accountTypes = new Set(['Viewer', 'User', 'Passkey', 'Session', 'PasskeyEdge', 'SessionEdge', 'PasskeyConnection', 'SessionConnection']);
  const source = environment.getStore().getSource() as MutableRecordSource;
  const removed = source.getRecordIDs().filter(id => {
    const type = source.get(id)?.__typename;
    return id === 'client:root:viewer' || id.startsWith('client:root:viewer:') || (typeof type === 'string' && accountTypes.has(type));
  });
  environment.commitUpdate(store => {
    for (const id of removed) store.delete(id);
    store.getRoot().setValue(null, 'viewer');
  });
  // Deletion notifies subscribers; removing its tombstones also forgets private
  // account/credential IDs when the store is subsequently serialized.
  // Our environments own a mutable RecordSource (relay/environment.ts).
  for (const id of removed) source.remove(id);
}

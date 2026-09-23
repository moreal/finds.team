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
    if (data.viewer.passkeys.error || data.viewer.sessions.error) return { failure: { status: (data.viewer.passkeys.error?.code ?? data.viewer.sessions.error?.code) === 'FORBIDDEN' ? 403 : 500 } };
    return {};
  } catch (error) {
    const failure = accountFailure(error);
    if (failure.status === 401 || failure.status === 403) clearAccountRecords(environment);
    return { failure };
  }
}

/** Remove protected account records when the session is no longer authorized. */
export function clearAccountRecords(environment: Environment) {
  const removed = new Set<string>();
  environment.commitUpdate(store => {
    const drop = (id: string) => { removed.add(id); store.delete(id); };
    const root = store.getRoot();
    const account = root.getLinkedRecord('viewer') ?? store.get('client:root:viewer');
    if (!account) return;
    const user = account.getLinkedRecord('user');
    if (user) drop(user.getDataID());
    for (const key of ['passkeys', 'sessions']) {
      // Relay connection handles coexist with their server fields. Delete the
      // entire account-owned graph below viewer, including paginated edges.
      const source = environment.getStore().getSource();
      const accountRecord = source.get(account.getDataID());
      for (const field of Object.keys(accountRecord ?? {}).filter(field => field.includes(key))) {
        const connection = account.getLinkedRecord(field);
        if (!connection) continue;
        for (const edge of connection.getLinkedRecords('edges') ?? []) {
          const node = edge?.getLinkedRecord('node');
          if (node) drop(node.getDataID());
          if (edge) drop(edge.getDataID());
        }
        const pageInfo = connection.getLinkedRecord('pageInfo');
        if (pageInfo) drop(pageInfo.getDataID());
        drop(connection.getDataID());
      }
    }
    drop(account.getDataID());
    root.setValue(null, 'viewer');
  });
  // Deletion notifies subscribers; removing its tombstones also forgets private
  // account/credential IDs when the store is subsequently serialized.
  // Our environments own a mutable RecordSource (relay/environment.ts).
  const source = environment.getStore().getSource() as MutableRecordSource;
  for (const id of removed) source.remove(id);
}

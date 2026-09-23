import { createSignal, For, onCleanup, onSettled, untrack } from 'solid-js';
import RelayRuntime, { type GraphQLTaggedNode, type Variables } from 'relay-runtime';
import type { AccountOperationsViewerQuery } from '../../__generated__/AccountOperationsViewerQuery.graphql';
import type { AccountOperations_user$key } from '../../__generated__/AccountOperations_user.graphql';
import * as operations from '../../relay/AccountOperations';
import { useRelayEnvironment } from '../../relay/RelayRoot';
import { GraphQLRequestError } from '../../relay/network';
import { readFragment } from '../../relay/fragments';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { Dialog } from '../../ui/Dialog';
import { Link } from '../../ui/Link';
import { RecoveryCodeDisplay } from './RecoveryCodeDisplay';
import { accountFailureMessage, clearAccountRecords, type AccountFailure } from './AccountPageQuery';
import './security.css';

type Viewer = NonNullable<AccountOperationsViewerQuery['response']['viewer']>;
type Payload = { outcome: string; error?: { code: string }; recoveryCode?: string };
type Confirmation = { title: string; description: string; button: string; document: GraphQLTaggedNode; field: string; input: Variables };
type PendingCommand = { document: GraphQLTaggedNode; field: string; input: Variables };
class AccountActionError extends Error {}
function mutationError(code?: string) {
  if (code === 'LAST_CREDENTIAL') return '마지막 Passkey는 삭제할 수 없어요.';
  if (code === 'FORBIDDEN') return '최근 Passkey 인증이 필요해요. 다시 로그인해 주세요.';
  if (code === 'IDEMPOTENCY_CONFLICT') return '요청이 충돌했어요. 상태를 새로 확인한 뒤 다시 시도해 주세요.';
  return '변경하지 못했어요. 입력과 로그인 상태를 확인해 주세요.';
}
function PasskeyRow(props: { item: Viewer['passkeys']['edges'][number]['node']; pending: boolean; onRename: (label: string) => void; onRemove: () => void }) {
  const [label, setLabel] = createSignal(props.item.label);
  return <li><form class="security-form" onSubmit={event => { event.preventDefault(); props.onRename(label().trim()); }}>
    <TextField label={`${props.item.label} 이름`} value={label()} required maxlength={80} disabled={props.pending} onInput={event => setLabel(event.currentTarget.value)} />
    <p>등록일 <time datetime={props.item.createdAt}>{props.item.createdAt.slice(0, 10)}</time>{props.item.lastUsedAt && <> · 최근 사용 {props.item.lastUsedAt.slice(0, 10)}</>}</p>
    <div class="security-actions"><Button type="submit" variant="secondary" disabled={props.pending}>이름 저장</Button><Button variant="danger" disabled={props.pending} onClick={props.onRemove}>{props.item.label} 삭제</Button></div>
  </form></li>;
}
export function SecurityPage(props: { initialViewer?: Viewer; initialFailure?: AccountFailure }) {
  const environment = useRelayEnvironment();
  const [viewer, setViewer] = createSignal<Viewer | undefined>(untrack(() => props.initialViewer));
  const [pending, setPending] = createSignal(false);
  const [ready, setReady] = createSignal(false);
  const [error, setError] = createSignal(untrack(() => props.initialFailure ? accountFailureMessage(props.initialFailure) : ''));
  const [message, setMessage] = createSignal('');
  const [code, setCode] = createSignal('');
  const [confirmation, setConfirmation] = createSignal<Confirmation>();
  const [unresolved, setUnresolved] = createSignal<PendingCommand>();
  const controlsDisabled = () => !ready() || pending() || !!unresolved();
  let disposed = false;
  onCleanup(() => { disposed = true; });
  onSettled(() => { setReady(true); });
  function clearProtectedState() {
    setViewer(undefined); setCode(''); setConfirmation(undefined); setUnresolved(undefined); setMessage('');
    clearAccountRecords(environment());
  }
  async function load(kind?: 'passkeys' | 'sessions') {
    const current = viewer();
    const variables = kind && current ? { [`${kind}After`]: current[kind].pageInfo.endCursor } : {};
    const result = await RelayRuntime.fetchQuery<AccountOperationsViewerQuery>(environment(), operations.viewer, variables, { fetchPolicy: 'network-only' }).toPromise();
    if (disposed) return;
    const next = result?.viewer;
    if (!next) { clearProtectedState(); setError('로그인이 필요해요. Passkey로 로그인해 주세요.'); }
    else {
      if (next.passkeys.error || next.sessions.error) throw new AccountActionError(mutationError(next.passkeys.error?.code ?? next.sessions.error?.code));
      setViewer(kind && current ? { ...current, [kind]: { ...next[kind], edges: [...current[kind].edges, ...next[kind].edges] } } : next);
    }
  }
  function failure(error: unknown) {
    if (error instanceof GraphQLRequestError) {
      if (error.status === 401 || error.status === 403) clearProtectedState();
      return accountFailureMessage({ status: error.status, correlationId: error.correlationId });
    }
    return error instanceof AccountActionError ? error.message : '요청을 완료하지 못했어요. 다시 시도해 주세요.';
  }
  async function refresh(kind?: 'passkeys' | 'sessions') {
    if (pending()) return; setPending(true); setError('');
    try { await load(kind); } catch (error) { setError(failure(error)); } finally { setPending(false); }
  }
  function mutate(document: GraphQLTaggedNode, field: string, input: Variables) {
    if (pending() || unresolved()) return;
    // Immutable logical command: all uncertain transport retries keep both
    // this UUID and the exact input. A separate explicit action starts a new one.
    const command = { document, field, input: { ...structuredClone(input), idempotencyKey: crypto.randomUUID() } };
    setUnresolved(command);
    void executeCommand(command);
  }
  async function executeCommand(command: PendingCommand) {
    if (pending()) return; setPending(true); setError(''); setMessage('');
    try {
      // Execute the compiled Relay operation through its network, without normalizing
      // a one-time recovery secret into Relay's long-lived store or devtools records.
      const result = await environment().getNetwork().execute(RelayRuntime.getRequest(command.document).params, { input: command.input }, {}).toPromise();
      if (!result || !('data' in result) || ('errors' in result && result.errors?.length)) throw new Error('요청을 완료하지 못했어요. 다시 시도해 주세요.');
      const payload = result.data?.[command.field] as Payload | undefined;
      // A semantic response resolves the command; a network failure does not.
      if (payload) setUnresolved(undefined);
      if (!payload || payload.error || payload.outcome === 'REJECTED') throw new AccountActionError(mutationError(payload?.error?.code));
      setConfirmation(undefined);
      if (payload.recoveryCode) setCode(payload.recoveryCode);
      else if (payload.outcome === 'ALREADY_ROTATED') setMessage('이미 발급된 복구 코드는 다시 표시할 수 없어요. 필요한 경우 새로 발급해 주세요.');
      else setMessage('변경했어요.');
      if (payload.outcome === 'SIGNED_OUT') { clearProtectedState(); setError('이 세션이 종료되었어요. 다시 로그인해 주세요.'); }
      else await load();
    } catch (error) { setError(failure(error)); } finally { setPending(false); }
  }
  function abandonCommand() { setUnresolved(undefined); setConfirmation(undefined); setError(''); }
  return <main class="security-page" data-account-id={viewer() ? readFragment<AccountOperations_user$key>(environment(), operations.user, viewer()!.user).id : undefined}><h1>계정 보안</h1>
    {code() ? <RecoveryCodeDisplay code={code()} onDone={() => setCode('')} /> : <>
      <p>Passkey와 복구 코드, 로그인된 기기를 관리하세요.</p>
      {error() && <p role="alert">{error()}</p>}<p role="status" aria-live="polite">{pending() ? '처리 중이에요.' : message()}</p>
      {!viewer() && <><Link href="/login">Passkey로 로그인</Link><Button variant="secondary" disabled={!ready() || pending()} onClick={() => void refresh()}>다시 시도</Button></>}
      {viewer() && <>
        <section aria-labelledby="passkeys-title"><h2 id="passkeys-title">Passkey</h2>
          <p>추가 Passkey 등록은 현재 준비 중이에요. 마지막 Passkey는 삭제할 수 없어요.</p>
          {!viewer()!.passkeys.edges.length && <p>등록된 Passkey가 없어요.</p>}
          <ul class="security-records"><For each={viewer()!.passkeys.edges}>{edge => <PasskeyRow item={edge.node} pending={controlsDisabled()}
            onRename={label => void mutate(operations.rename, 'renamePasskey', { passkeyId: edge.node.id, label })}
            onRemove={() => setConfirmation({ title: 'Passkey 삭제', description: `${edge.node.label}을 삭제하면 이 Passkey로 로그인할 수 없어요.`, button: '삭제 확인', document: operations.remove, field: 'removePasskey', input: { passkeyId: edge.node.id } })} />}</For></ul>
          {viewer()!.passkeys.pageInfo.hasNextPage && <Button variant="secondary" disabled={controlsDisabled()} onClick={() => void refresh('passkeys')}>Passkey 더 보기</Button>}
        </section>
        <section><h2>복구 코드</h2><p>새 코드를 발급하면 이전 코드는 사용할 수 없어요. 최근 Passkey 인증이 필요해요.</p>
          <Button variant="secondary" disabled={controlsDisabled()} onClick={() => setConfirmation({ title: '복구 코드 새로 발급', description: '이전 복구 코드를 취소하고 새로운 코드를 한 번만 표시해요. 안전한 곳에 저장할 준비가 되었나요?', button: '발급 확인', document: operations.rotate, field: 'rotateRecoveryCode', input: {} })}>복구 코드 새로 발급</Button>
        </section>
        <section><h2>로그인 세션</h2><ul class="security-records"><For each={viewer()!.sessions.edges}>{edge => <li><strong>{edge.node.current ? '현재 세션' : '다른 세션'}</strong><p>시작 {edge.node.createdAt.slice(0, 10)} · 만료 {edge.node.expiresAt.slice(0, 10)}</p>
          <Button variant="danger" disabled={controlsDisabled()} onClick={() => setConfirmation({ title: '세션 종료', description: edge.node.current ? '현재 기기에서 로그아웃돼요.' : '이 세션에서 다시 로그인해야 해요.', button: '종료 확인', document: operations.revoke, field: 'revokeSession', input: { sessionId: edge.node.id } })}>{edge.node.current ? '현재 세션 종료' : '이 세션 종료'}</Button>
        </li>}</For></ul>
          {viewer()!.sessions.pageInfo.hasNextPage && <Button variant="secondary" disabled={controlsDisabled()} onClick={() => void refresh('sessions')}>세션 더 보기</Button>}
          <Button variant="danger" disabled={controlsDisabled()} onClick={() => setConfirmation({ title: '다른 세션 모두 종료', description: '현재 세션을 제외한 모든 기기에서 로그아웃돼요.', button: '종료 확인', document: operations.revokeOthers, field: 'revokeOtherSessions', input: {} })}>다른 세션 모두 종료</Button>
        </section>
        {unresolved() && !confirmation() && <section aria-label="완료되지 않은 변경"><p>응답을 받지 못했어요. 이미 처리되었을 수 있으니 같은 요청을 다시 확인해 주세요.</p><Button disabled={pending()} onClick={() => void executeCommand(unresolved()!)}>같은 변경 다시 시도</Button><Button variant="ghost" disabled={pending()} onClick={abandonCommand}>현재 요청의 재시도 중단</Button></section>}
        {error() && !unresolved() && <div class="security-actions"><Button variant="secondary" disabled={pending()} onClick={() => void refresh()}>다시 불러오기</Button><Link href="/login">Passkey로 다시 인증</Link></div>}
      </>}
      {confirmation() && <Dialog trigger="변경 확인" title={confirmation()!.title} description={confirmation()!.description} closeLabel="취소" open onOpenChange={open => { if (!open && !pending()) setConfirmation(undefined); }}>
        {error() && <p role="alert">{error()}</p>}{unresolved() && <p>이미 처리되었을 수 있어요. 같은 요청을 다시 확인해도 새 코드를 중복 발급하지 않아요.</p>}
        <Button variant="danger-confirm" loading={pending()} onClick={() => { const retry = unresolved(); if (retry) void executeCommand(retry); else { const command = confirmation()!; mutate(command.document, command.field, command.input); } }}>{unresolved() ? '같은 변경 다시 시도' : confirmation()!.button}</Button>
      </Dialog>}
    </>}
  </main>;
}

import { createSignal, For, onSettled, untrack } from 'solid-js';
import RelayRuntime, { type GraphQLTaggedNode, type Variables } from 'relay-runtime';
import type { AdminOperationsSitesQuery } from '../../__generated__/AdminOperationsSitesQuery.graphql';
import type { AdminOperationsStatusesQuery } from '../../__generated__/AdminOperationsStatusesQuery.graphql';
import type { AdminOperationsHistoryQuery } from '../../__generated__/AdminOperationsHistoryQuery.graphql';
import type { AdminOperationsAuditQuery } from '../../__generated__/AdminOperationsAuditQuery.graphql';
import type { AdminOperations_audit$key } from '../../__generated__/AdminOperations_audit.graphql';
import * as operations from '../../relay/AdminOperations';
import { readQuery, readFragment } from '../../relay/fragments';
import { useRelayEnvironment } from '../../relay/RelayRoot';
import { GraphQLRequestError } from '../../relay/network';
import { adminFailure, checkAdmin, assertConnection, fetchAdmin, queryFor, readAdmin, loadAdminRelations, clearAdminRecords, type AdminLoad } from './AdminData';
import { accountFailureMessage } from '../security/AccountPageQuery';
import { loginPasskey, securityError } from '../security/webauthn';
import { Button } from '../../ui/Button';
import { Link } from '../../ui/Link';
import { Badge } from '../../ui/Badge';
import { TextField } from '../../ui/TextField';
import { Dialog } from '../../ui/Dialog';
import { AuditFilters, type AuditSearch } from './AuditFilters';
import { RegisterSiteDialog } from './RegisterSiteDialog';
import { SiteDetail } from './SiteDetail';
import './admin.css';

type Command = { document: GraphQLTaggedNode; field: string; input: Variables; actorId?: string };
type Payload = { error?: { code: string }; outcome?: string; runId?: string; site?: { id: string } };
type Status = AdminOperationsStatusesQuery['response']['crawlStatuses']['edges'][number]['node'];
export function attention(status: Pick<Status, 'outcome' | 'finishedAt' | 'runId'>, now: number) {
  if (status.outcome === 'FAILED') return { rank: 0, label: '실패', tone: 'danger' as const };
  if (status.outcome === 'SUCCESS' && (!status.finishedAt || now - Date.parse(status.finishedAt) > 86400000)) return { rank: 1, label: '오래됨', tone: 'warning' as const };
  if (!status.outcome && status.runId) return { rank: 2, label: '실행 중', tone: 'action' as const };
  if (!status.outcome) return { rank: 1, label: '수집 기록 없음', tone: 'warning' as const };
  return { rank: 3, label: '정상', tone: 'success' as const };
}
export function AdminDashboard(props: { load: AdminLoad }) {
  const environment = useRelayEnvironment();
  const initial = untrack(() => props.load);
  const [failure, setFailure] = createSignal(initial.failure);
  const [sites, setSites] = createSignal(untrack(() => !initial.failure && ['dashboard', 'sites'].includes(initial.kind) ? readQuery<AdminOperationsSitesQuery>(environment(), operations.sites, {}).careerSites : undefined));
  const [statuses, setStatuses] = createSignal(untrack(() => !initial.failure && ['dashboard', 'sites'].includes(initial.kind) ? readQuery<AdminOperationsStatusesQuery>(environment(), operations.statuses, {}).crawlStatuses : undefined));
  const [site, setSite] = createSignal(untrack(() => !initial.failure && initial.kind === 'detail' ? readQuery<AdminOperationsHistoryQuery>(environment(), operations.history, initial.variables as { slug: string }).careerSite : undefined));
  const [audit, setAudit] = createSignal(untrack(() => !initial.failure && initial.kind === 'audit' ? readQuery<AdminOperationsAuditQuery>(environment(), operations.auditPage, initial.variables).auditEvents : undefined));
  const [pending, setPendingSignal] = createSignal(false);
  // Solid 2 publishes signal writes after the current turn. Command ownership
  // must change synchronously so two submit/retry events cannot create writes.
  let busy = false;
  function setPending(value: boolean) { busy = value; setPendingSignal(value); }
  const [ready, setReady] = createSignal(false);
  const [error, setError] = createSignal('');
  const [message, setMessage] = createSignal('');
  const [dialog, setDialog] = createSignal<'register' | 'crawl'>();
  let dialogOpener: HTMLButtonElement | undefined;
  function openDialog(kind: 'register' | 'crawl', opener: HTMLButtonElement) { dialogOpener = opener; setDialog(kind); }
  function closeDialog() {
    setDialog(undefined);
    onSettled(() => { if (dialogOpener?.isConnected) dialogOpener.focus(); });
  }
  const [command, setCommandSignal] = createSignal<Command>();
  let activeCommand: Command | undefined;
  function setCommand(value: Command | undefined) { activeCommand = value; setCommandSignal(value); }
  const [stepUp, setStepUp] = createSignal(false);
  onSettled(() => { setReady(true); });
  const disabled = () => !ready() || pending() || !!command();
  const connection = () => initial.kind === 'dashboard' ? statuses() : initial.kind === 'sites' ? sites() : initial.kind === 'detail' ? site()?.crawlHistory : audit();
  const filteredSites = () => sites()?.edges.filter(edge => edge.node.displayName.toLocaleLowerCase().includes(String(initial.variables.q ?? '').toLocaleLowerCase())) ?? [];
  const title = () => ({ dashboard: '수집 현황', sites: '사이트 관리', detail: '사이트 상세', audit: '감사 기록' })[initial.kind];
  function handleError(error: unknown) {
    const problem = adminFailure(error);
    if (problem.status === 401 || problem.status === 403) { clearAdminRecords(environment()); setFailure(problem); setSites(undefined); setStatuses(undefined); setSite(undefined); setAudit(undefined); }
    return accountFailureMessage(problem);
  }
  async function loadMore() {
    if (busy || activeCommand) return;
    setPending(true); setError('');
    try {
      await checkAdmin(environment());
      const variables = { ...initial.variables, after: connection()?.pageInfo.endCursor };
      await fetchAdmin(environment(), queryFor[initial.kind], variables);
      assertConnection(readAdmin(environment(), initial.kind, variables));
      if (initial.kind === 'dashboard' || initial.kind === 'sites') {
        await loadAdminRelations(environment(), initial.kind);
        setSites(readQuery<AdminOperationsSitesQuery>(environment(), operations.sites, {}).careerSites);
        setStatuses(readQuery<AdminOperationsStatusesQuery>(environment(), operations.statuses, {}).crawlStatuses);
      }
      if (initial.kind === 'dashboard') {
        const next = readQuery<AdminOperationsStatusesQuery>(environment(), operations.statuses, variables).crawlStatuses;
        setStatuses(next);
      } else if (initial.kind === 'sites') {
        const next = readQuery<AdminOperationsSitesQuery>(environment(), operations.sites, variables).careerSites;
        setSites(next);
      } else if (initial.kind === 'audit') {
        const next = readQuery<AdminOperationsAuditQuery>(environment(), operations.auditPage, variables).auditEvents;
        setAudit(next);
      } else {
        const next = readQuery<AdminOperationsHistoryQuery>(environment(), operations.history, { ...variables, slug: String(initial.variables.slug) }).careerSite!;
        setSite(next);
      }
    } catch (error) { setError(handleError(error)); } finally { setPending(false); }
  }
  async function execute(next: Command, resumeAuthenticated = false) {
    if (busy && !resumeAuthenticated) return;
    setPending(true); setError(''); setMessage('');
    let mutationStarted = false;
    try {
      const actorId = await checkAdmin(environment());
      if (next.actorId && actorId !== next.actorId) { setCommand(undefined); setStepUp(false); throw new Error('Account changed'); }
      next.actorId = actorId;
      mutationStarted = true;
      const result = await environment().getNetwork().execute(RelayRuntime.getRequest(next.document).params, { input: next.input }, {}).toPromise();
      if (!result || !('data' in result) || ('errors' in result && result.errors?.length)) throw new Error('Uncertain response');
      const payload = result.data?.[next.field] as Payload | undefined;
      if (!payload) throw new Error('Uncertain response');
      if (payload.error?.code === 'FORBIDDEN' || payload.outcome === 'FORBIDDEN') {
        mutationStarted = false;
        await checkAdmin(environment());
        setStepUp(true); setError('최근 Passkey 인증이 필요해요. 인증 후 같은 요청을 이어서 실행해요.'); return;
      }
      setCommand(undefined); setStepUp(false);
      if (payload.error) { setError(payload.error.code === 'IDEMPOTENCY_CONFLICT' ? '요청이 충돌했어요. 상태를 새로 확인해 주세요.' : '요청을 처리하지 못했어요. 입력과 사이트 상태를 확인해 주세요.'); return; }
      closeDialog();
      if (payload.site) setMessage('사이트를 등록했어요. 목록을 새로 불러오면 확인할 수 있어요.');
      else setMessage(`수집 결과: ${payload.outcome ?? '확인됨'}${payload.runId ? ` · ${payload.runId}` : ''}`);
    } catch (error) {
      if (mutationStarted && error instanceof GraphQLRequestError && error.status === 403) {
        try { await checkAdmin(environment()); setStepUp(true); setError('최근 Passkey 인증이 필요해요. 인증 후 같은 요청을 이어서 실행해요.'); }
        catch (gateError) { setError(handleError(gateError)); }
      }
      else setError(handleError(error));
    } finally { setPending(false); }
  }
  function start(document: GraphQLTaggedNode, field: string, input: Variables) {
    if (busy || activeCommand || disabled()) return;
    const next = { document, field, input: { ...structuredClone(input), idempotencyKey: crypto.randomUUID() } };
    setCommand(next); void execute(next);
  }
  async function authenticate() {
    if (busy) return;
    setPending(true); setError('');
    try {
      const result = await loginPasskey();
      if (!result.authenticated) throw new Error('Passkey 인증을 확인하지 못했어요.');
      setStepUp(false);
      if (command()) await execute(command()!, true);
    } catch (error) { setError(securityError(error)); } finally { setPending(false); }
  }
  return <main class="admin-page"><h1>{title()}</h1>
    {failure() ? <><p role="alert">{failure()!.status === 404 ? '사이트를 찾을 수 없어요.' : accountFailureMessage(failure()!)}</p><Link href="/jobs">채용 공고로 돌아가기</Link><Link href={initial.kind === 'detail' ? `/admin/sites/${encodeURIComponent(String(initial.variables.slug))}` : `/admin${initial.kind === 'dashboard' ? '' : `/${initial.kind}`}`}>다시 시도</Link></> : <>
      <nav aria-label="관리자 메뉴"><Link href="/admin">수집 현황</Link><Link href="/admin/sites">사이트 관리</Link><Link href="/admin/audit">감사 기록</Link></nav>
      <p role="status" aria-live="polite">{pending() ? '처리 중이에요.' : message()}</p>
      {!dialog() && error() && <p role="alert">{error()}</p>}
      {initial.kind === 'dashboard' && <><p>실패 → 오래됨 → 실행 중 → 정상 순서예요. 완료 후 24시간이 지나면 오래됨으로 표시해요. 불러온 {statuses()?.edges.length ?? 0} / {statuses()?.totalCount ?? 0}개 사이트 기준이에요.</p>
        <ul class="admin-records"><For each={[...(statuses()?.edges ?? [])].sort((a, b) => attention(a.node, initial.now).rank - attention(b.node, initial.now).rank)}>{edge => {
          const source = () => sites()?.edges.find(site => site.node.id === edge.node.careerSiteId)?.node;
          const status = () => attention(edge.node, initial.now);
          return <li>{source() ? <Link href={`/admin/sites/${encodeURIComponent(source()!.slug)}`}>{source()!.displayName}</Link> : <span>{edge.node.careerSiteId}</span>}<Badge data-status tone={status().tone}>{status().label}</Badge><p>{edge.node.finishedAt ?? '완료 시각 없음'}</p></li>;
        }}</For></ul></>}
      {initial.kind === 'sites' && <><Button disabled={disabled()} onClick={event => openDialog('register', event.currentTarget)}>사이트 등록</Button>
        <form class="admin-filters" action="/admin/sites" method="get"><TextField label="사이트 이름 검색" name="q" value={String(initial.variables.q ?? '')} /><Button type="submit">사이트 필터 적용</Button></form>
        <p>불러온 사이트에서 검색해요. {sites()?.edges.length ?? 0} / {sites()?.totalCount ?? 0}개를 불러왔어요.</p>
        {!!initial.variables.q && !filteredSites().length && <p>“{String(initial.variables.q)}”에 맞는 사이트가 없어요. <Link href="/admin/sites">이름 필터 해제</Link></p>}
        <ul class="admin-records"><For each={filteredSites()}>{edge => {
          const current = () => statuses()?.edges.find(status => status.node.careerSiteId === edge.node.id)?.node;
          return <li><Link href={`/admin/sites/${encodeURIComponent(edge.node.slug)}`}>{edge.node.displayName}</Link><p>{edge.node.provider} · {current() ? attention(current()!, initial.now).label : '수집 상태 없음'}</p></li>;
        }}</For></ul></>}
      {initial.kind === 'detail' && site() && <><SiteDetail site={site()!} /><section><h2>수집 실행</h2><p>사이트의 채용 정보를 다시 수집해요. 최근 Passkey 인증이 필요해요.</p><Button disabled={disabled()} onClick={event => openDialog('crawl', event.currentTarget)}>지금 수집</Button></section></>}
      {initial.kind === 'audit' && <><AuditFilters search={(initial.variables.filter ?? {}) as AuditSearch} /><ol class="admin-records"><For each={audit()?.edges}>{edge => {
        const event = () => readFragment<AdminOperations_audit$key>(environment(), operations.audit, edge.node);
        return <li><h2>{event().action}</h2><time datetime={event().occurredAt}>{event().occurredAt}</time><p>{event().actorUserId ?? event().actorKind} · {event().targetType} / {event().targetId} · {event().outcome}</p></li>;
      }}</For></ol></>}
      {connection()?.edges.length === 0 && <p>{initial.kind === 'audit' && Object.keys(initial.variables.filter ?? {}).length ? '필터에 맞는 감사 기록이 없어요.' : '아직 기록이 없어요.'} <Link href={initial.kind === 'audit' ? '/admin/audit' : '/admin/sites'}>전체 보기</Link></p>}
      {connection()?.pageInfo.hasNextPage && <Button variant="secondary" disabled={disabled()} onClick={() => void loadMore()}>더 보기</Button>}
      {dialog() && <Dialog trigger="작업 확인" title={dialog() === 'register' ? '사이트 등록' : '수집 실행 확인'} description={dialog() === 'crawl' ? '이 사이트의 수집 작업을 실행할까요?' : '자동 수집할 채용 페이지를 등록하세요.'} closeLabel="닫기" open onOpenChange={open => { if (!open && !pending()) closeDialog(); }}>
        {error() && <p role="alert">{error()}</p>}
        {!command() && (dialog() === 'register' ? <RegisterSiteDialog disabled={disabled()} onConfirm={input => start(operations.register, 'registerCareerSite', input)} /> : <Button disabled={disabled()} onClick={() => start(operations.trigger, 'triggerCrawl', { careerSiteId: site()!.id })}>수집 확인</Button>)}
        {command() && <><p>응답이 불확실하면 동일한 요청으로 결과를 확인해요.</p><Button disabled={pending()} onClick={() => void (stepUp() ? authenticate() : execute(command()!))}>{stepUp() ? 'Passkey 인증 후 계속' : '같은 요청 다시 시도'}</Button></>}
      </Dialog>}
      {command() && !dialog() && <Button disabled={pending()} onClick={() => void (stepUp() ? authenticate() : execute(command()!))}>{stepUp() ? 'Passkey 인증 후 계속' : '같은 요청 다시 시도'}</Button>}
    </>}
  </main>;
}

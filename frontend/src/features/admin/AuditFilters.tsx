import { TextField } from '../../ui/TextField';
import { Button } from '../../ui/Button';
export type AuditSearch = { actorUserId?: string; action?: string; targetType?: string; targetId?: string; from?: string; until?: string };
const actions = ['ROLE_GRANTED', 'ROLE_REVOKED', 'CAREER_SITE_REGISTERED', 'CAREER_SITE_SETTINGS_CHANGED', 'MANUAL_CRAWL_TRIGGERED', 'PASSKEY_REGISTERED', 'PASSKEY_REMOVED', 'PASSKEY_RENAMED', 'RECOVERY_CODE_ROTATED', 'ACCOUNT_RECOVERY_COMPLETED', 'SESSION_REVOKED', 'ADMIN_CONFIGURATION_CHANGED'];
export function auditSearch(input: Record<string, unknown>): AuditSearch {
  const output: AuditSearch = {};
  for (const key of ['actorUserId', 'action', 'targetType', 'targetId', 'from', 'until'] as const) {
    const value = input[key];
    if (typeof value !== 'string' || !value.trim()) continue;
    if (key === 'action' && !actions.includes(value)) continue;
    if ((key === 'from' || key === 'until') && !Number.isFinite(Date.parse(value))) continue;
    output[key] = key === 'from' || key === 'until' ? new Date(value).toISOString() : value.trim();
  }
  return output;
}
export function AuditFilters(props: { search: AuditSearch }) {
  return <form class="admin-filters" action="/admin/audit" method="get">
    <TextField label="행위자 ID" name="actorUserId" value={props.search.actorUserId ?? ''} />
    <label>작업<select name="action"><option value="">모든 작업</option>{actions.map(action => <option value={action} selected={props.search.action === action}>{action}</option>)}</select></label>
    <TextField label="대상 유형" name="targetType" value={props.search.targetType ?? ''} />
    <TextField label="대상 ID" name="targetId" value={props.search.targetId ?? ''} />
    <TextField label="시작 시각 (UTC)" name="from" type="datetime-local" value={props.search.from?.slice(0, 16) ?? ''} />
    <TextField label="종료 시각 (UTC)" name="until" type="datetime-local" value={props.search.until?.slice(0, 16) ?? ''} />
    <Button type="submit">필터 적용</Button>
  </form>;
}

import { afterEach, expect, it, vi } from 'vitest';
import { createServerRelayEnvironment } from '../../../relay/environment';
import { loadAdmin, fetchAdmin } from '../AdminData';
import { auditPage } from '../../../relay/AdminOperations';

afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
const auditResponse = (forbidden = false) => ({ data: { auditEvents: {
  edges: [{ cursor: 'private-cursor', node: { __typename: 'AuditEvent', id: 'private-event', occurredAt: '2026-09-20T00:00:00Z', actorKind: 'USER', actorUserId: 'private-actor', action: 'CAREER_SITE_REGISTERED', targetType: 'career_site', targetId: 'private-target', outcome: 'SUCCEEDED', details: { role: null, provider: 'FLEX', enabled: true } } }],
  totalCount: 1, error: forbidden ? { code: 'FORBIDDEN', message: 'private-diagnostic' } : null,
  pageInfo: { hasNextPage: false, hasPreviousPage: false, startCursor: 'private-cursor', endCursor: 'private-cursor' },
} } });
it('rejects semantic forbidden data before protected audit records enter the store', async () => {
  vi.stubEnv('FINDS_INTERNAL_GRAPHQL_URL', 'http://backend.test/graphql');
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(auditResponse(true))));
  const environment = createServerRelayEnvironment(new Request('http://finds.test/admin/audit'));
  await expect(fetchAdmin(environment, auditPage, {})).rejects.toMatchObject({ status: 403 });
  expect(JSON.stringify(environment.getStore().getSource().toJSON())).not.toContain('private');
});
it('forgets previously normalized audit records when administrator membership is revoked', async () => {
  vi.stubEnv('FINDS_INTERNAL_GRAPHQL_URL', 'http://backend.test/graphql');
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(Response.json({ data: { viewer: { user: { id: 'user', roles: ['ADMIN'] } } } }))
    .mockResolvedValueOnce(Response.json(auditResponse()))
    .mockResolvedValueOnce(Response.json({ data: { viewer: { user: { id: 'user', roles: ['USER'] } } } })));
  const environment = createServerRelayEnvironment(new Request('http://finds.test/admin/audit'));
  environment.commitUpdate(store => { store.create('public-site', 'CareerSite').setValue('Public source', 'displayName'); });
  expect((await loadAdmin(environment, 'audit')).failure).toBeUndefined();
  expect(JSON.stringify(environment.getStore().getSource().toJSON())).toContain('private-event');
  expect((await loadAdmin(environment, 'audit')).failure).toEqual({ status: 403 });
  expect(JSON.stringify(environment.getStore().getSource().toJSON())).not.toContain('private');
  expect(environment.getStore().getSource().get('public-site')?.displayName).toBe('Public source');
});

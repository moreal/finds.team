import { afterEach, expect, it, vi } from 'vitest';
import { createServerRelayEnvironment } from '../../../relay/environment';
import { clearAccountRecords, loadAccountPage } from '../AccountPageQuery';

afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

it.each([401, 403])('clears request-owned protected records when a later viewer load returns HTTP %s', async status => {
  vi.stubEnv('FINDS_INTERNAL_GRAPHQL_URL', 'http://backend.test/graphql');
  const connection = { edges: [], totalCount: 0, error: null, pageInfo: { hasNextPage: false, hasPreviousPage: false, startCursor: null, endCursor: null } };
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(Response.json({ data: { viewer: { user: { id: 'private-account-marker', roles: ['USER'] }, passkeys: connection, sessions: connection } } }))
    .mockResolvedValueOnce(new Response(null, { status })));
  const environment = createServerRelayEnvironment(new Request('http://finds.test/account/security', { headers: { cookie: 'session=private-account-marker' } }));
  expect(await loadAccountPage(environment)).toEqual({});
  expect(JSON.stringify(environment.getStore().getSource().toJSON())).toContain('private-account-marker');
  expect(await loadAccountPage(environment)).toMatchObject({ failure: { status } });
  expect(JSON.stringify(environment.getStore().getSource().toJSON())).not.toContain('private-account-marker');
});

function populatedViewer(keys: string[], sessions: string[]) {
  const connection = (nodes: { id: string }[]) => ({ edges: nodes.map(node => ({ cursor: node.id, node })), totalCount: nodes.length, error: null as { code: string; message: string } | null,
    pageInfo: { hasNextPage: false, hasPreviousPage: false, startCursor: nodes[0]?.id ?? null, endCursor: nodes.at(-1)?.id ?? null } });
  return { data: { viewer: {
    user: { id: 'private-user', roles: ['USER'] },
    passkeys: connection(keys.map(id => ({ __typename: 'Passkey', id, label: `${id} label`, createdAt: '2026-09-20T00:00:00Z', lastUsedAt: null }))),
    sessions: connection(sessions.map(id => ({ __typename: 'Session', id, createdAt: '2026-09-20T00:00:00Z', expiresAt: '2026-09-25T00:00:00Z', current: false }))),
  } } };
}

it('clears detached historical account nodes and edges after shrinking connections while preserving public records', async () => {
  vi.stubEnv('FINDS_INTERNAL_GRAPHQL_URL', 'http://backend.test/graphql');
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(Response.json(populatedViewer(['old-private-key', 'current-private-key'], ['old-private-session', 'current-private-session'])))
    .mockResolvedValueOnce(Response.json(populatedViewer(['current-private-key'], ['current-private-session']))));
  const environment = createServerRelayEnvironment(new Request('http://finds.test/account/security'));
  environment.commitUpdate(store => {
    for (const [id, type] of [['public-posting', 'JobPosting'], ['public-company', 'CareerSite'], ['public-skill', 'Skill']]) {
      store.create(id, type).setValue(`${id} remains`, 'label');
    }
  });
  const publicBefore = ['public-posting', 'public-company', 'public-skill'].map(id => environment.getStore().getSource().get(id));
  expect(await loadAccountPage(environment)).toEqual({});
  expect(await loadAccountPage(environment)).toEqual({});
  expect(environment.getStore().getSource().get('old-private-key')?.label).toBe('old-private-key label');
  clearAccountRecords(environment);
  const serialized = JSON.stringify(environment.getStore().getSource().toJSON());
  expect(serialized).not.toContain('private');
  expect(serialized).not.toContain('client:root:viewer');
  expect(['public-posting', 'public-company', 'public-skill'].map(id => environment.getStore().getSource().get(id))).toEqual(publicBefore);
});

it.each(['passkeys', 'sessions', 'mixed'] as const)('semantic %s FORBIDDEN removes protected records before SSR serialization', async field => {
  vi.stubEnv('FINDS_INTERNAL_GRAPHQL_URL', 'http://backend.test/graphql');
  const response = populatedViewer(['private-key'], ['private-session']);
  response.data.viewer[field === 'mixed' ? 'sessions' : field].error = { code: 'FORBIDDEN', message: 'private backend diagnostic' };
  if (field === 'mixed') response.data.viewer.passkeys.error = { code: 'INTERNAL', message: 'private other diagnostic' };
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(response)));
  const environment = createServerRelayEnvironment(new Request('http://finds.test/account/security'));
  expect(await loadAccountPage(environment)).toMatchObject({ failure: { status: 403 } });
  expect(JSON.stringify(environment.getStore().getSource().toJSON())).not.toContain('private');
});

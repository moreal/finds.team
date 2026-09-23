import { afterEach, expect, it, vi } from 'vitest';
import { createServerRelayEnvironment } from '../../../relay/environment';
import { loadAccountPage } from '../AccountPageQuery';

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

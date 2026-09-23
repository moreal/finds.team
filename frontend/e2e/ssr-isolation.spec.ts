import { expect, test } from '@playwright/test';
import { runInNewContext } from 'node:vm';

test.use({ baseURL: 'http://127.0.0.1:4176' });
test('concurrent authenticated SSR keeps viewer and site records request-local', async ({ request }) => {
  const identities = ['isolation-alice-unique', 'isolation-bob-unique'];
  // Repeated overlapping pairs catch reuse after a previous request as well as
  // simultaneous store sharing. Both users use identical site record IDs.
  for (let round = 0; round < 3; round++) {
    const html = await Promise.all(identities.map(async identity => {
      const response = await request.get('/admin', { headers: { cookie: `admin-role=ADMIN; isolation-session=${identity}` } });
      expect(response.status()).toBe(200);
      return response.text();
    }));
    for (let i = 0; i < identities.length; i++) {
      const own = identities[i];
      const other = identities[1 - i];
      const scripts = [...html[i].matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map(match => match[1]).join('\n');
      const markup = html[i].replace(/<script\b[^>]*>[\s\S]*?<\/script>/g, '');
      expect(markup).toContain(`${own}-site`);
      expect(scripts).toContain('relayRecords');
      expect(scripts).toContain(`${own}-viewer`);
      expect(scripts).toContain(`${own}-site`);
      // Decode the trusted fixture's Start payload, then inspect the actual
      // normalized store rather than accepting markers in unrelated scripts.
      const payload = [...html[i].matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)]
        .map(match => match[1]).find(script => script.includes('$_TSR.router='));
      expect(payload).toBeDefined();
      const sandbox: any = { document: { currentScript: { remove() {} } } };
      sandbox.self = sandbox;
      runInNewContext(payload!, sandbox, { timeout: 1000 });
      const records = sandbox.$_TSR.router.dehydratedData.relayRecords;
      expect(records[`${own}-viewer`]).toMatchObject({ __typename: 'User', id: `${own}-viewer` });
      expect(records.failed).toMatchObject({ __typename: 'CareerSite', displayName: `${own}-site-failed` });
      expect(JSON.stringify(records)).not.toContain(other);
      expect(html[i]).not.toContain(other);
      expect(scripts).not.toContain(other);
    }
  }
});

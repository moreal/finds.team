import { expect, it } from 'vitest';
import { auditSearch } from '../AuditFilters';
it('interprets the UTC-labelled naive form timestamp independently of host timezone', () => {
  expect(auditSearch({ from: '2026-09-24T09:30', until: '2026-09-24T10:30+09:00' })).toEqual({ from: '2026-09-24T09:30:00.000Z', until: '2026-09-24T01:30:00.000Z' });
});
it('preserves an opaque site filter without decoding it', () => {
  expect(auditSearch({ atCareerSite: 'opaque/site==' })).toEqual({ atCareerSite: 'opaque/site==' });
});

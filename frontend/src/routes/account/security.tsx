import { createFileRoute } from '@tanstack/solid-router';
import { SecurityPage } from '../../features/security/SecurityPage';
export const Route = createFileRoute('/account/security')({ head: () => ({ meta: [{ title: '계정 보안 | finds.team' }, { name: 'robots', content: 'noindex' }] }), component: SecurityPage });

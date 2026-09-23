import { createFileRoute } from '@tanstack/solid-router';
import { EnrollmentPage } from '../features/security/EnrollmentPage';
export const Route = createFileRoute('/recover')({ head: () => ({ meta: [{ title: '계정 복구 | finds.team' }, { name: 'robots', content: 'noindex' }] }), component: () => <EnrollmentPage mode="recovery" /> });

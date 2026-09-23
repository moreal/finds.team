import { createFileRoute } from '@tanstack/solid-router';
import { EnrollmentPage } from '../features/security/EnrollmentPage';
export const Route = createFileRoute('/join')({ head: () => ({ meta: [{ title: '계정 만들기 | finds.team' }, { name: 'robots', content: 'noindex' }] }), component: () => <EnrollmentPage mode="enrollment" /> });

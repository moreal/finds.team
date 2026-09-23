import { createFileRoute } from '@tanstack/solid-router';
import { LoginPage } from '../features/security/LoginPage';
export const Route = createFileRoute('/login')({ head: () => ({ meta: [{ title: '로그인 | finds.team' }, { name: 'robots', content: 'noindex' }] }), component: LoginPage });
